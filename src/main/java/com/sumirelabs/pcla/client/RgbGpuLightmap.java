package com.sumirelabs.pcla.client;

import com.sumirelabs.pcla.config.RuntimeOptions;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Render-thread GPU resources; workers only read the volatile encoding-ready flag. */
public final class RgbGpuLightmap {
    public static final boolean REQUESTED=RuntimeOptions.rgbLightmap();
    private static final int RAW_UNIT=3;
    private static final boolean CELERITAS=RgbGpuLightmap.class.getClassLoader().getResource("org/embeddedt/embeddium/impl/render/chunk/ShaderChunkRenderer.class")!=null;
    private static final float[] raw=new float[256*3];
    private static final FloatBuffer rawBuffer=BufferUtils.createFloatBuffer(256*4);
    private static int captured,program,rawTexture,expandedTexture;
    private static Uniforms compatUniforms;
    private record Uniforms(int raw,int gamma,int night,int lighting,int light0,int light1,int radial,
                            int entityOverride,int entityLight,int entityFloor) {
        Uniforms(int program) {
            this(location(program,"pcla_RawLightmap"),location(program,"pcla_Gamma"),location(program,"pcla_NightVision"),
                location(program,"pcla_Lighting"),location(program,"pcla_Light0"),location(program,"pcla_Light1"),
                location(program,"pcla_RadialFog"),location(program,"pcla_EntityOverride"),
                location(program,"pcla_EntityLight"),location(program,"pcla_EntityFloor"));
        }
        private static int location(int program,String name){return GL20.glGetUniformLocation(program,name);}
    }
    private static boolean failed;
    private static boolean radialFogSupported;
    private static boolean cubeAllocated;
    private static volatile boolean ready;
    private static Thread renderThread;
    private static RgbLightmapCurve curve;
    private static int[] nativeColors;
    public static String failure="";
    public static long uploads,terrainPasses,entityPasses;
    public static boolean ready(){return ready;}
    /** Chunk workers cannot query GL. Immediate custom shader draws retain scalar UVs. */
    public static boolean encodeMesh() {
        if(!ready)return false;
        if(Thread.currentThread()!=renderThread)return true;
        int active=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        return active==0 || active==program;
    }
    public static int program(){return program;}
    static int rawTexture(){return rawTexture;}
    static int expandedTexture(){return expandedTexture;}
    public static RgbLightmapCurve curve(){return curve;}
    static int[] nativeColors(){return nativeColors.clone();}
    public static void beginCapture(){captured=0;}
    public static void captureCell(int index,float[] colors) {
        if(!REQUESTED)return;
        if(index!=captured || colors.length<3){captured=-1000;return;}
        System.arraycopy(colors,0,raw,index*3,3);captured++;
    }
    public static void completeCapture(int[] nativeColors,float gamma,float night) {
        if(!REQUESTED || failed || captured!=256)return;
        try {
            var next=new RgbLightmapCurve(raw,gamma,night);
            if(program==0)initialize();
            upload(next,nativeColors);curve=next;RgbGpuLightmap.nativeColors=nativeColors.clone();uploads++;
            if(!ready) {
                ready=true;
                // Previously compiled fallback quads must be rebuilt exactly once when encoding becomes available.
                ClientProxy.invalidate(ClientProxy.STORE.snapshot());
            }
        } catch(RuntimeException e) {
            failure=e.toString();failed=true;ready=false;
            org.apache.logging.log4j.LogManager.getLogger("PCLA").error("RGB lightmap initialization failed; retaining legacy display",e);
        }
    }
    public static String source(String file) {
        try(var in=RgbGpuLightmap.class.getResourceAsStream("/assets/pcla/shaders/"+file)) {
            if(in==null)throw new IllegalStateException("Missing RGB shader "+file);
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        } catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private static void initialize() {
        renderThread=Thread.currentThread();
        if(GL11.glGetInteger(GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS)<1)
            throw new IllegalStateException("RGB lightmaps require vertex texture sampling");
        String extensions=GL11.glGetString(GL11.GL_EXTENSIONS);
        radialFogSupported=extensions!=null && extensions.contains("GL_NV_fog_distance");
        int shader=GL20.glCreateShader(GL20.GL_VERTEX_SHADER),linked=0;
        try {
            GL20.glShaderSource(shader,source("compat_lightmap.vsh").replace("// PCLA_LIGHTMAP_FUNCTIONS",source("rgb_lightmap.glsl")));
            GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader,16384));
            linked=GL20.glCreateProgram();GL20.glAttachShader(linked,shader);GL20.glLinkProgram(linked);
            if(GL20.glGetProgrami(linked,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(linked,16384));
            program=linked;linked=0;
            compatUniforms=new Uniforms(program);
            rawTexture=GL11.glGenTextures();expandedTexture=GL11.glGenTextures();
            org.apache.logging.log4j.LogManager.getLogger("PCLA").info("RGB GPU lightmap program ready: {}",program);
        } finally {GL20.glDeleteShader(shader);if(linked!=0)GL20.glDeleteProgram(linked);}
    }
    private static void parameters() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
    }
    private static void upload(RgbLightmapCurve next,int[] colors) {
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0+RAW_UNIT);
        int old=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            rawBuffer.clear();float[] values=next.raw();
            for(int i=0;i<256;i++)rawBuffer.put(values[i*3]).put(values[i*3+1]).put(values[i*3+2]).put(1);
            rawBuffer.flip();GlStateManager.bindTexture(rawTexture);parameters();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA32F,16,16,0,GL11.GL_RGBA,GL11.GL_FLOAT,rawBuffer);
            int oldCube=GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
            try {
                GL11.glBindTexture(GL12.GL_TEXTURE_3D,expandedTexture);
                GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
                GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
                GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL12.GL_TEXTURE_3D,GL12.GL_TEXTURE_WRAP_R,GL12.GL_CLAMP_TO_EDGE);
                if(!cubeAllocated) {
                    var identity=BufferUtils.createFloatBuffer(16*16*18*4);
                    for(int z=0;z<18;z++)for(int y=0;y<16;y++)for(int x=0;x<16;x++)
                        identity.put(x/15.0f).put(y/15.0f).put(Math.max(0,z-1)/16.0f).put(1);
                    identity.flip();GL12.glTexImage3D(GL12.GL_TEXTURE_3D,0,GL30.GL_RGBA16F,16,16,18,0,GL11.GL_RGBA,GL11.GL_FLOAT,identity);
                    cubeAllocated=true;
                }
                rawBuffer.clear();
                for(int c:colors)rawBuffer.put((c>>16&255)/255.0f).put((c>>8&255)/255.0f).put((c&255)/255.0f).put((c>>>24)/255.0f);
                rawBuffer.flip();GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D,0,0,0,0,16,16,1,GL11.GL_RGBA,GL11.GL_FLOAT,rawBuffer);
            } finally {GL11.glBindTexture(GL12.GL_TEXTURE_3D,oldCube);}
        } finally {GlStateManager.bindTexture(old);GlStateManager.setActiveTexture(active);}
    }
    public static void uniforms(int targetProgram) {
        if(curve==null)return;
        int sampler=targetProgram==program?compatUniforms.raw:GL20.glGetUniformLocation(targetProgram,"pcla_RawLightmap");
        if(sampler<0)return;
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0+RAW_UNIT);GlStateManager.bindTexture(rawTexture);GlStateManager.setActiveTexture(active);
        GL20.glUniform1i(sampler,RAW_UNIT);
        GL20.glUniform1f(targetProgram==program?compatUniforms.gamma:GL20.glGetUniformLocation(targetProgram,"pcla_Gamma"),curve.gamma());
        GL20.glUniform1f(targetProgram==program?compatUniforms.night:GL20.glGetUniformLocation(targetProgram,"pcla_NightVision"),curve.night());
    }
    public static Scope terrain(){terrainPasses++;return new Scope(true);}
    public static Scope terrainLayer(){terrainPasses++;return new Scope(!CELERITAS);}
    public static Scope entity(){entityPasses++;return new Scope(true);}
    public static final class Scope implements AutoCloseable {
        private final int previousProgram,active,lightTexture,previousRaw;
        private final boolean cubeEnabled;
        private boolean nested;
        private boolean rawEnabled;
        private boolean bound;
        private Scope(boolean compatible) {
            previousProgram=ready?GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM):0;
            if(!ready || !compatible || previousProgram!=0 && previousProgram!=program){active=lightTexture=previousRaw=0;cubeEnabled=false;return;}
            // The outer draw already owns the texture/program bindings. Painting tiles and
            // immediate-mode subdraws must not query/rebind every resource for every quad.
            if(previousProgram==program){active=lightTexture=previousRaw=0;cubeEnabled=false;nested=bound=true;return;}
            active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE1);lightTexture=GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
            cubeEnabled=GL11.glIsEnabled(GL12.GL_TEXTURE_3D);GL11.glBindTexture(GL12.GL_TEXTURE_3D,expandedTexture);
            if(GL11.glIsEnabled(GL11.GL_TEXTURE_2D))GL11.glEnable(GL12.GL_TEXTURE_3D);else GL11.glDisable(GL12.GL_TEXTURE_3D);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0+RAW_UNIT);previousRaw=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            // GLSL vertex sampling ignores fixed-function enables. Other mods may leave
            // this unit enabled; the raw table must never multiply the fragment color.
            rawEnabled=GL11.glIsEnabled(GL11.GL_TEXTURE_2D);GL11.glDisable(GL11.GL_TEXTURE_2D);
            GlStateManager.setActiveTexture(active);
            GL20.glUseProgram(program);uniforms(program);bound=true;lightingState();entityState();
        }
        public void close() {
            if(!bound)return;bound=false;
            if(nested)return;
            GL20.glUseProgram(previousProgram);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE1);GL11.glBindTexture(GL12.GL_TEXTURE_3D,lightTexture);
            if(cubeEnabled)GL11.glEnable(GL12.GL_TEXTURE_3D);else GL11.glDisable(GL12.GL_TEXTURE_3D);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0+RAW_UNIT);GlStateManager.bindTexture(previousRaw);
            if(rawEnabled)GL11.glEnable(GL11.GL_TEXTURE_2D);else GL11.glDisable(GL11.GL_TEXTURE_2D);
            GlStateManager.setActiveTexture(active);
        }
        public boolean bound(){return bound;}
    }
    public static void lightingState() {
        if(!ready || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)!=program)return;
        flag(compatUniforms.lighting,GL11.glIsEnabled(GL11.GL_LIGHTING));
        flag(compatUniforms.light0,GL11.glIsEnabled(GL11.GL_LIGHT0));flag(compatUniforms.light1,GL11.glIsEnabled(GL11.GL_LIGHT1));
        flag(compatUniforms.radial,radialFogSupported && GL11.glGetInteger(0x855a)==0x855b);
    }
    public static void entityState() {
        if(!ready || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)!=program)return;
        var light=GpuEntityLight.current();boolean enabled=light!=null && RgbLightCoordinates.encoded(light.packed());
        flag(compatUniforms.entityOverride,enabled);
        if(enabled) {
            GL20.glUniform2f(compatUniforms.entityLight,(short)light.packed(),(short)(light.packed()>>>16));
            GL20.glUniform1f(compatUniforms.entityFloor,light.scalarBlock());
        }
    }
    public static boolean active(){return ready && GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)==program;}
    private static void flag(int location,boolean enabled){GL20.glUniform1i(location,enabled?1:0);}
    public static void textureState() {
        if(!ready || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)!=program || GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)!=GL13.GL_TEXTURE1)return;
        if(GL11.glIsEnabled(GL11.GL_TEXTURE_2D))GL11.glEnable(GL12.GL_TEXTURE_3D);else GL11.glDisable(GL12.GL_TEXTURE_3D);
    }
    public static void clear(){ready=false;curve=null;nativeColors=null;captured=0;}
    public static void shutdown() {
        clear();if(program!=0)GL20.glDeleteProgram(program);program=0;compatUniforms=null;
        if(rawTexture!=0)GL11.glDeleteTextures(rawTexture);rawTexture=0;
        if(expandedTexture!=0)GL11.glDeleteTextures(expandedTexture);expandedTexture=0;cubeAllocated=false;
    }
    private RgbGpuLightmap(){}
}

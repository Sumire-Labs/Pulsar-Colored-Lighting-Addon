package com.sumirelabs.pcla.client;

import com.google.gson.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.util.Random;

/** Offscreen GPU/CPU comparison. All GL state is restored before returning to the game. */
public final class GpuLightmapProbe {
    public static JsonObject run() {
        if(!RgbGpuLightmap.ready())throw new IllegalStateException("RGB GPU lightmap is not ready");
        int oldProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),oldActive=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);int oldRawBinding=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);GL13.glActiveTexture(oldActive);
        int oldMode=GL11.glGetInteger(GL11.GL_MATRIX_MODE),oldDraw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),oldRead=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int fbo=GL30.glGenFramebuffers(),target=GL11.glGenTextures(),white=GL11.glGenTextures();
        var report=new JsonObject();report.addProperty("preexistingGlError",GL11.glGetError());
        report.addProperty("shaderPath","PCLA compatibility shader with synthetic immediate-mode coordinates");
        report.addProperty("scope","Lightmap math and GL state only; does not test Celeritas shaders, terrain mesh encoding or world sampling");
        report.addProperty("vendor",GL11.glGetString(GL11.GL_VENDOR));
        report.addProperty("renderer",GL11.glGetString(GL11.GL_RENDERER));
        report.addProperty("openGlVersion",GL11.glGetString(GL11.GL_VERSION));
        var cases=new JsonArray();report.add("cases",cases);int compared=0,mismatches=0,maxError=0;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPushMatrix();GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPushMatrix();GL11.glLoadIdentity();
        GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glMatrixMode(GL11.GL_TEXTURE);GL11.glPushMatrix();GL11.glLoadIdentity();
        GL11.glScalef(1.0f/256,1.0f/256,1);GL11.glTranslatef(8,8,0);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,target);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(java.nio.ByteBuffer)null);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,target,0);
            if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("RGB probe framebuffer incomplete");
            GL11.glViewport(0,0,1,1);
            for(int state:new int[]{GL11.GL_BLEND,GL11.GL_DEPTH_TEST,GL11.GL_ALPHA_TEST,GL11.GL_CULL_FACE,GL11.GL_FOG,
                    GL11.GL_LIGHTING,GL11.GL_SCISSOR_TEST,GL11.GL_STENCIL_TEST,GL11.GL_COLOR_LOGIC_OP,GL30.GL_FRAMEBUFFER_SRGB})GL11.glDisable(state);
            for(int clip=0;clip<6;clip++)GL11.glDisable(GL11.GL_CLIP_PLANE0+clip);
            GL11.glColorMask(true,true,true,true);
            for(int unit=0;unit<4;unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_TEXTURE_1D);GL11.glDisable(GL12.GL_TEXTURE_3D);GL11.glDisable(GL13.GL_TEXTURE_CUBE_MAP);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glEnable(GL12.GL_TEXTURE_3D);GL11.glBindTexture(GL12.GL_TEXTURE_3D,RgbGpuLightmap.expandedTexture());
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,GL11.GL_TEXTURE_ENV_MODE,GL11.GL_MODULATE);
            GL13.glActiveTexture(GL13.GL_TEXTURE3);GL11.glBindTexture(GL11.GL_TEXTURE_2D,RgbGpuLightmap.rawTexture());
            int program=RgbGpuLightmap.program();GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"pcla_RawLightmap"),3);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"pcla_Lighting"),0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"pcla_RadialFog"),0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"pcla_EntityOverride"),0);
            var pixel=BufferUtils.createByteBuffer(4);float[] raw=RgbGpuLightmap.curve().raw();
            for(float gamma:new float[]{0,0.5f,1})for(float night:new float[]{0,0.5f,1}) {
                var reference=new RgbLightmapCurve(raw,gamma,night);
                GL20.glUniform1f(GL20.glGetUniformLocation(program,"pcla_Gamma"),gamma);
                GL20.glUniform1f(GL20.glGetUniformLocation(program,"pcla_NightVision"),night);
                var random=new Random(4129);
                for(int index=0;index<64;index++) {
                    float[] block=new float[3],sky=new float[3];
                    for(int c=0;c<3;c++){block[c]=index<16?index:random.nextInt(31)*0.5f;sky[c]=index<16?15-index:random.nextInt(31)*0.5f;}
                    int b=RgbLightCoordinates.lane(block[0],block[1],block[2]),s=RgbLightCoordinates.lane(sky[0],sky[1],sky[2]);
                    GL13.glMultiTexCoord2f(GL13.GL_TEXTURE1,(short)b,(short)s);GL11.glColor4f(1,1,1,1);
                    draw(pixel);
                    float[] expected=reference.sample(block,sky);int[] actual=new int[3],want=new int[3];
                    for(int c=0;c<3;c++){actual[c]=pixel.get(c)&255;want[c]=Math.round(expected[c]*255);int error=Math.abs(actual[c]-want[c]);maxError=Math.max(maxError,error);if(error>2)mismatches++;compared++;}
                    if(index<4 || mismatches>0 && cases.size()<96) {
                        var item=new JsonObject();item.addProperty("gamma",gamma);item.addProperty("night",night);
                        item.add("block",new Gson().toJsonTree(block));item.add("sky",new Gson().toJsonTree(sky));
                        item.add("expected",new Gson().toJsonTree(want));item.add("actual",new Gson().toJsonTree(actual));cases.add(item);
                    }
                }
            }
            int nativeErrors=0,nativeMax=0;int[] nativeColors=RgbGpuLightmap.nativeColors();
            for(int i=0;i<256;i++) {
                GL13.glMultiTexCoord2f(GL13.GL_TEXTURE1,(i&15)*16,(i>>4)*16);GL11.glColor4f(1,1,1,1);draw(pixel);
                for(int c=0;c<3;c++) {
                    int error=Math.abs((pixel.get(c)&255)-(nativeColors[i]>>(16-c*8)&255));nativeMax=Math.max(nativeMax,error);if(error>1)nativeErrors++;
                }
            }
            report.addProperty("nativeComparedChannels",768);report.addProperty("nativeMismatchesOverOne",nativeErrors);report.addProperty("nativeMaximumByteError",nativeMax);
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"pcla_Gamma"),0);GL20.glUniform1f(GL20.glGetUniformLocation(program,"pcla_NightVision"),0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"pcla_EntityOverride"),1);
            GL20.glUniform2f(GL20.glGetUniformLocation(program,"pcla_EntityLight"),(short)RgbLightCoordinates.lane(15,3,1),(short)RgbLightCoordinates.lane(0,0,0));
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"pcla_EntityFloor"),96);
            var entityReference=new RgbLightmapCurve(raw,0,0);var entityColor=entityReference.sample(new float[]{15,3,1},new float[]{0,0,0});
            int entityErrors=0,entityMax=0;
            for(int i=0;i<17;i++) {
                boolean explicit=i==16;
                GL13.glMultiTexCoord2f(GL13.GL_TEXTURE1,explicit?(short)RgbLightCoordinates.lane(0,2,15):i*16,
                    explicit?(short)RgbLightCoordinates.lane(0,0,0):80);
                GL11.glColor4f(1,1,1,1);draw(pixel);
                float[] explicitColor=explicit?entityReference.sample(new float[]{0,2,15},new float[]{0,0,0}):null;
                for(int c=0;c<3;c++) {
                    int expected=explicit?Math.round(explicitColor[c]*255):i<=7?Math.round(entityColor[c]*255):(nativeColors[5*16+i]>>(16-c*8)&255);
                    int error=Math.abs((pixel.get(c)&255)-expected);entityMax=Math.max(entityMax,error);if(error>2)entityErrors++;
                }
            }
            // An explicitly fullbright layer remains native even beside a level-15 colored source.
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"pcla_EntityFloor"),240);
            GL13.glMultiTexCoord2f(GL13.GL_TEXTURE1,240,240);draw(pixel);
            for(int c=0;c<3;c++) {
                int error=Math.abs((pixel.get(c)&255)-(nativeColors[255]>>(16-c*8)&255));
                entityMax=Math.max(entityMax,error);if(error>2)entityErrors++;
            }
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"pcla_EntityOverride"),0);
            report.addProperty("entityComparedChannels",54);report.addProperty("entityMismatchesOverTwo",entityErrors);report.addProperty("entityMaximumByteError",entityMax);
            // Vanilla RenderLivingBase's hurt overlay: interpolate red with material, then multiply by texture unit 1.
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            env(GL11.GL_TEXTURE_ENV_MODE,GL13.GL_COMBINE);env(GL13.GL_COMBINE_RGB,GL13.GL_INTERPOLATE);
            env(GL13.GL_SOURCE0_RGB,GL13.GL_CONSTANT);env(GL13.GL_SOURCE1_RGB,GL13.GL_PREVIOUS);env(GL13.GL_SOURCE2_RGB,GL13.GL_CONSTANT);
            env(GL13.GL_OPERAND0_RGB,GL11.GL_SRC_COLOR);env(GL13.GL_OPERAND1_RGB,GL11.GL_SRC_COLOR);env(GL13.GL_OPERAND2_RGB,GL11.GL_SRC_ALPHA);
            env(GL13.GL_COMBINE_ALPHA,GL11.GL_REPLACE);env(GL13.GL_SOURCE0_ALPHA,GL13.GL_PREVIOUS);env(GL13.GL_OPERAND0_ALPHA,GL11.GL_SRC_ALPHA);
            var hurt=BufferUtils.createFloatBuffer(4);hurt.put(new float[]{1,0,0,0.3f}).flip();
            net.minecraft.client.renderer.GlStateManager.glTexEnv(GL11.GL_TEXTURE_ENV,GL11.GL_TEXTURE_ENV_COLOR,hurt);
            GL13.glActiveTexture(GL13.GL_TEXTURE2);GL11.glEnable(GL11.GL_TEXTURE_2D);GL11.glBindTexture(GL11.GL_TEXTURE_2D,white);
            var whitePixel=BufferUtils.createByteBuffer(4);whitePixel.putInt(-1).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,1,1,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,whitePixel);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            env(GL11.GL_TEXTURE_ENV_MODE,GL13.GL_COMBINE);env(GL13.GL_COMBINE_RGB,GL11.GL_MODULATE);
            env(GL13.GL_SOURCE0_RGB,GL13.GL_PREVIOUS);env(GL13.GL_SOURCE1_RGB,GL13.GL_TEXTURE1);
            env(GL13.GL_OPERAND0_RGB,GL11.GL_SRC_COLOR);env(GL13.GL_OPERAND1_RGB,GL11.GL_SRC_COLOR);
            env(GL13.GL_COMBINE_ALPHA,GL11.GL_REPLACE);env(GL13.GL_SOURCE0_ALPHA,GL13.GL_PREVIOUS);env(GL13.GL_OPERAND0_ALPHA,GL11.GL_SRC_ALPHA);
            GL20.glUniform1f(GL20.glGetUniformLocation(program,"pcla_Gamma"),0);GL20.glUniform1f(GL20.glGetUniformLocation(program,"pcla_NightVision"),0);
            var overlayReference=new RgbLightmapCurve(raw,0,0);int overlayErrors=0,overlayMax=0;
            for(int i=0;i<16;i++) {
                float[] block={i,15-i,3},sky={0,4,15};float[] light=overlayReference.sample(block,sky);
                GL13.glMultiTexCoord2f(GL13.GL_TEXTURE1,(short)RgbLightCoordinates.lane(block[0],block[1],block[2]),(short)RgbLightCoordinates.lane(sky[0],sky[1],sky[2]));
                GL11.glColor4f(0.6f,0.4f,0.8f,0.7f);draw(pixel);
                float[] factor={0.72f,0.28f,0.56f};
                for(int c=0;c<4;c++) {
                    int expected=Math.round((c==3?0.7f:factor[c]*light[c])*255);
                    int error=Math.abs((pixel.get(c)&255)-expected);overlayMax=Math.max(overlayMax,error);if(error>2)overlayErrors++;
                }
            }
            report.addProperty("hurtComparedChannels",64);report.addProperty("hurtMismatchesOverTwo",overlayErrors);report.addProperty("hurtMaximumByteError",overlayMax);
            // Reproduce a foreign renderer leaving the raw sampler's fixed-function stage enabled.
            GL20.glUseProgram(0);GL13.glActiveTexture(GL13.GL_TEXTURE2);GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glEnable(GL11.GL_TEXTURE_2D);env(GL11.GL_TEXTURE_ENV_MODE,GL11.GL_MODULATE);
            GL13.glActiveTexture(GL13.GL_TEXTURE3);GL11.glEnable(GL11.GL_TEXTURE_2D);env(GL11.GL_TEXTURE_ENV_MODE,GL11.GL_MODULATE);
            int rawErrors=0;
            try(var scope=RgbGpuLightmap.entity()) {
                GL13.glActiveTexture(GL13.GL_TEXTURE3);report.addProperty("rawStageDisabledDuringDraw",!GL11.glIsEnabled(GL11.GL_TEXTURE_2D));
                GL13.glMultiTexCoord2f(GL13.GL_TEXTURE1,(short)RgbLightCoordinates.lane(15,3,1),(short)RgbLightCoordinates.lane(0,0,0));
                GL11.glColor4f(1,1,1,1);draw(pixel);
                float[] expected=RgbGpuLightmap.curve().sample(new float[]{15,3,1},new float[]{0,0,0});
                for(int c=0;c<3;c++)if(Math.abs((pixel.get(c)&255)-Math.round(expected[c]*255))>2)rawErrors++;
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE3);report.addProperty("rawStageEnableRestored",GL11.glIsEnabled(GL11.GL_TEXTURE_2D));
            report.addProperty("rawStageComparedChannels",3);report.addProperty("rawStageMismatchesOverTwo",rawErrors);
            report.addProperty("glError",GL11.glGetError());report.addProperty("comparedChannels",compared);
            report.addProperty("mismatchesOverTwo",mismatches);report.addProperty("maximumByteError",maxError);
            return report;
        } finally {
            GL20.glUseProgram(oldProgram);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);
            GL30.glDeleteFramebuffers(fbo);GL11.glDeleteTextures(target);GL11.glDeleteTextures(white);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);GL11.glMatrixMode(GL11.GL_TEXTURE);GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);GL11.glPopMatrix();GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPopMatrix();
            GL11.glPopAttrib();
            // The scoped regression test uses the engine's texture cache; reconcile it
            // after restoring raw GL attributes, including when another mod bypassed it.
            GL13.glActiveTexture(GL13.GL_TEXTURE3);net.minecraft.client.renderer.GlStateManager.setActiveTexture(GL13.GL_TEXTURE3);
            net.minecraft.client.renderer.GlStateManager.bindTexture(0);net.minecraft.client.renderer.GlStateManager.bindTexture(oldRawBinding);
            GL13.glActiveTexture(oldActive);net.minecraft.client.renderer.GlStateManager.setActiveTexture(oldActive);GL11.glMatrixMode(oldMode);
            // Probe overrides belong to this owned shader and must not survive the test.
            if(oldProgram==RgbGpuLightmap.program()){RgbGpuLightmap.uniforms(oldProgram);RgbGpuLightmap.lightingState();RgbGpuLightmap.entityState();}
        }
    }
    private static void env(int key,int value){GL11.glTexEnvi(GL11.GL_TEXTURE_ENV,key,value);}
    private static void draw(java.nio.ByteBuffer pixel) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3f(-1,-1,0);GL11.glVertex3f(1,-1,0);GL11.glVertex3f(1,1,0);GL11.glVertex3f(-1,1,0);GL11.glEnd();
        pixel.clear();GL11.glReadPixels(0,0,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixel);
    }
    private GpuLightmapProbe(){}
}

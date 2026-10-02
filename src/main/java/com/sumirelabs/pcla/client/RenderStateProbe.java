package com.sumirelabs.pcla.client;

import com.google.gson.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.file.*;

/** One explicitly requested hand frame; no GL queries during ordinary rendering. */
public final class RenderStateProbe {
    private static boolean requested;
    private static JsonArray samples;
    public static void request(){requested=true;}
    public static void buffer(net.minecraft.client.renderer.vertex.VertexFormat format){if(samples!=null)mark("bufferDraw:"+format);}
    public static void begin(){if(requested){requested=false;samples=new JsonArray();mark("beforeScope");}}
    public static void mark(String stage) {
        if(samples==null || samples.size()>=24)return;
        var entry=new JsonObject();entry.addProperty("stage",stage);
        entry.addProperty("program",GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));entry.addProperty("pclaProgram",RgbGpuLightmap.program());
        entry.addProperty("lighting",GL11.glIsEnabled(GL11.GL_LIGHTING));entry.addProperty("light0",GL11.glIsEnabled(GL11.GL_LIGHT0));entry.addProperty("light1",GL11.glIsEnabled(GL11.GL_LIGHT1));
        for(int field:new int[]{GL11.GL_CURRENT_COLOR,GL11.GL_CURRENT_NORMAL,GL11.GL_LIGHT_MODEL_AMBIENT}) {
            var buffer=BufferUtils.createFloatBuffer(4);GL11.glGetFloat(field,buffer);
            var values=new JsonArray();for(int i=0;i<(field==GL11.GL_CURRENT_NORMAL?3:4);i++)values.add(buffer.get(i));entry.add(Integer.toHexString(field),values);
        }
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);var textures=new JsonArray();
        for(int unit=0;unit<4;unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);var texture=new JsonObject();
            texture.addProperty("unit",unit);texture.addProperty("2d",GL11.glIsEnabled(GL11.GL_TEXTURE_2D));texture.addProperty("3d",GL11.glIsEnabled(GL12.GL_TEXTURE_3D));
            texture.addProperty("cube",GL11.glIsEnabled(GL13.GL_TEXTURE_CUBE_MAP));
            texture.addProperty("binding2d",GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));texture.addProperty("binding3d",GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D));
            var coords=BufferUtils.createFloatBuffer(4);GL11.glGetFloat(GL11.GL_CURRENT_TEXTURE_COORDS,coords);var values=new JsonArray();for(int i=0;i<4;i++)values.add(coords.get(i));texture.add("coords",values);textures.add(texture);
        }
        GL13.glActiveTexture(active);entry.add("textures",textures);samples.add(entry);
    }
    public static void end() {
        if(samples==null)return;var output=samples;samples=null;
        try{Files.writeString(Path.of("logs","pcla-hand-state-"+System.currentTimeMillis()+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(output));}
        catch(java.io.IOException e){org.apache.logging.log4j.LogManager.getLogger("PCLA").error("Cannot save hand state",e);}
    }
    private RenderStateProbe(){}
}

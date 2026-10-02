package com.sumirelabs.pcla.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import java.io.BufferedWriter;
import java.nio.ByteBuffer;
import java.nio.file.*;

/** Explicit pixel observation. GL readback adds diagnostic overhead; never active during ordinary play. */
public final class VisualLatencyProbe {
    private static final ByteBuffer PIXELS=BufferUtils.createByteBuffer(8*8*4);
    private static BufferedWriter output;
    private static Path path;
    private static long started,next;
    private static Object world;
    public static void start() {
        var mc=Minecraft.getMinecraft();
        if(!Boolean.getBoolean("pcla.networkDiagnostics") || mc.world==null)
            throw new IllegalStateException("Pixel probe requires a world and networkDiagnostics");
        if(output!=null)throw new IllegalStateException("Pixel probe already running");
        try {
            path=Path.of("logs","pcla-visual-latency-"+System.currentTimeMillis()+".jsonl");
            Files.createDirectories(path.getParent());output=Files.newBufferedWriter(path);
            var header=new JsonObject();header.addProperty("type","header");
            header.addProperty("scope","8x8 world pixels right of crosshair; GL readback adds overhead; timestamps use local host wall clock");
            header.addProperty("x",mc.player.posX);header.addProperty("y",mc.player.posY);header.addProperty("z",mc.player.posZ);
            header.addProperty("yaw",mc.player.rotationYaw);header.addProperty("pitch",mc.player.rotationPitch);
            header.addProperty("width",mc.displayWidth);header.addProperty("height",mc.displayHeight);
            output.write(new Gson().toJson(header));output.newLine();output.flush();
            world=mc.world;started=System.nanoTime();next=0;
        } catch(java.io.IOException e){close();throw new IllegalStateException("Cannot start pixel probe",e);}
    }
    @SubscribeEvent public void render(RenderWorldLastEvent event) {
        if(output==null)return;
        var mc=Minecraft.getMinecraft();long now=System.nanoTime();
        if(mc.world!=world || now-started>120_000_000_000L){close();return;}
        if(now<next)return;next=now+8_000_000L;
        try {
            PIXELS.clear();GL11.glReadPixels(mc.displayWidth/2+24,mc.displayHeight/2,8,8,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,PIXELS);
            int r=0,g=0,b=0;for(int i=0;i<64;i++){r+=PIXELS.get(i*4)&255;g+=PIXELS.get(i*4+1)&255;b+=PIXELS.get(i*4+2)&255;}
            output.write("{\"timeMs\":"+System.currentTimeMillis()+",\"elapsedMs\":"+(System.nanoTime()-started)/1e6
                +",\"rgb\":["+r/64.0+","+g/64.0+","+b/64.0+"],\"deltas\":"+ClientProxy.deltasApplied+"}");
            output.newLine();output.flush();
        } catch(Exception e){org.apache.logging.log4j.LogManager.getLogger("PCLA").error("Pixel probe failed",e);close();}
    }
    public static void close() {
        if(output==null)return;
        try{output.close();}catch(java.io.IOException ignored){}finally{output=null;world=null;}
        var mc=Minecraft.getMinecraft();if(mc.player!=null)mc.player.sendMessage(new TextComponentString("PCLA pixel probe saved: "+path));
    }
}

package com.sumirelabs.pcla.client;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.Arrays;

/** Explicit diagnostic only. Measures CPU render work and whole frame intervals separately. */
public final class RenderPerformanceProbe {
    private static Run run;
    private static final class Run {
        final Object world=Minecraft.getMinecraft().world;
        final long requested=System.nanoTime();
        final double[] render=new double[120000],interval=new double[120000];
        long started,previous,frame,allocatedStart,cpuStart;
        int count;
    }
    public static void start() {
        if(!Boolean.getBoolean("pcla.networkDiagnostics") || Minecraft.getMinecraft().world==null)
            throw new IllegalStateException("A world and networkDiagnostics are required");
        if(run!=null)throw new IllegalStateException("A render probe is already active");
        run=new Run();
    }
    @SubscribeEvent public void render(TickEvent.RenderTickEvent event) {
        var r=run;if(r==null)return;
        var mc=Minecraft.getMinecraft();
        if(mc.world!=r.world){run=null;return;}
        long now=System.nanoTime();
        if(now-r.requested<5_000_000_000L)return;
        if(event.phase==TickEvent.Phase.START) {
            if(r.started==0) {
                r.started=now;r.allocatedStart=allocated();r.cpuStart=cpu();
            }
            if(now-r.started>=30_000_000_000L || r.count==r.render.length){finish(r,now);run=null;return;}
            r.frame=now;
        } else if(r.frame!=0) {
            r.render[r.count]=(now-r.frame)/1e6;
            r.interval[r.count]=r.previous==0?0:(now-r.previous)/1e6;
            r.previous=now;r.count++;r.frame=0;
        }
    }
    private static long allocated() {
        var bean=ManagementFactory.getThreadMXBean();
        return bean instanceof com.sun.management.ThreadMXBean ext && ext.isThreadAllocatedMemorySupported()
            && ext.isThreadAllocatedMemoryEnabled()?ext.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1;
    }
    private static long cpu(){var bean=ManagementFactory.getThreadMXBean();return bean.isCurrentThreadCpuTimeSupported()?bean.getCurrentThreadCpuTime():-1;}
    private static JsonObject distribution(double[] values,int from,int count) {
        var result=new JsonObject();if(count<=from)return result;
        var sorted=Arrays.copyOfRange(values,from,count);Arrays.sort(sorted);
        result.addProperty("medianMs",sorted[sorted.length/2]);
        result.addProperty("p95Ms",sorted[(int)Math.floor((sorted.length-1)*0.95)]);
        result.addProperty("p99Ms",sorted[(int)Math.floor((sorted.length-1)*0.99)]);
        result.addProperty("maxMs",sorted[sorted.length-1]);return result;
    }
    private static void finish(Run r,long now) {
        var mc=Minecraft.getMinecraft();var report=new JsonObject();
        report.addProperty("scope","30-second diagnostic after 5-second warmup; not a general game benchmark");
        report.addProperty("frames",r.count);report.addProperty("seconds",(now-r.started)/1e9);
        report.addProperty("sampleLimitReached",r.count==r.render.length);
        report.add("cpuRenderWork",distribution(r.render,0,r.count));report.add("wholeFrameInterval",distribution(r.interval,1,r.count));
        long bytes=allocated(),cpu=cpu();
        report.addProperty("clientThreadAllocatedBytes",bytes<0 || r.allocatedStart<0?-1:bytes-r.allocatedStart);
        report.addProperty("clientThreadCpuMillis",cpu<0 || r.cpuStart<0?-1:(cpu-r.cpuStart)/1e6);
        report.addProperty("gpuLightmap",RgbGpuLightmap.ready());report.addProperty("blockRendering",BlockTint.enabled);
        report.addProperty("entityRendering",EntityTint.enabled);report.addProperty("gamma",mc.gameSettings.gammaSetting);
        report.addProperty("vsync",mc.gameSettings.enableVsync);report.addProperty("frameLimit",mc.gameSettings.limitFramerate);
        report.addProperty("renderDistance",mc.gameSettings.renderDistanceChunks);
        report.addProperty("dimension",mc.world.provider.getDimension());
        report.addProperty("x",mc.player.posX);report.addProperty("y",mc.player.posY);report.addProperty("z",mc.player.posZ);
        report.addProperty("yaw",mc.player.rotationYaw);report.addProperty("pitch",mc.player.rotationPitch);
        var path=Path.of("logs","pcla-render-perf-"+System.currentTimeMillis()+".json");
        try {
            Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report));
            mc.player.sendMessage(new TextComponentString("PCLA render probe saved: "+path));
        } catch(java.io.IOException e){org.apache.logging.log4j.LogManager.getLogger("PCLA").error("Cannot write render probe",e);}
    }
}

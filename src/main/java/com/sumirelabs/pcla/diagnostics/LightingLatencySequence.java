package com.sumirelabs.pcla.diagnostics;

import com.google.gson.*;
import com.sumirelabs.pcla.light.world.ExperimentalRgbBackend;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.nio.file.*;

/** Explicit console-only test on a preselected air cell, restored on completion/shutdown. */
public final class LightingLatencySequence {
    private static WorldServer world;
    private static BlockPos source;
    private static int ticks,step;
    private static JsonArray records;
    private static JsonObject current;
    private static Path path;
    private static int previousSample;
    public static void start(WorldServer target,BlockPos pos) {
        if(world!=null)throw new IllegalStateException("Latency sequence already active");
        if(!target.isBlockLoaded(pos) || !target.isAirBlock(pos))throw new IllegalArgumentException("Loaded air cell required");
        world=target;source=pos.toImmutable();ticks=step=0;records=new JsonArray();current=null;
        path=Path.of("logs","pcla-edit-latency-"+System.currentTimeMillis()+".json");
        save();
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || world==null)return;
        try {
            if(!world.isBlockLoaded(source)){stop();return;}
            ticks++;
            if(current!=null && !current.has("rgbReadyMs")) {
                int value=sample();
                if(value>=0 && value!=previousSample){current.addProperty("rgbReadyMs",System.currentTimeMillis());current.addProperty("rgbSample",value);save();}
            }
            if(ticks%60!=0)return;
            if(step==20){stop();return;}
            var block=world.getBlockState(source).getBlock();
            if(block!=Blocks.AIR && block!=Blocks.GLOWSTONE){stop();return;}
            previousSample=sample();boolean on=step%2==0;
            current=new JsonObject();current.addProperty("step",step++);current.addProperty("on",on);
            current.addProperty("editMs",System.currentTimeMillis());
            current.addProperty("x",source.getX());current.addProperty("y",source.getY());current.addProperty("z",source.getZ());
            records.add(current);world.setBlockState(source,(on?Blocks.GLOWSTONE:Blocks.AIR).getDefaultState(),3);save();
        } catch(RuntimeException failure){org.apache.logging.log4j.LogManager.getLogger("PCLA").error("Latency sequence failed",failure);stop();}
    }
    private static int sample() {
        var target=source.east();var backend=ExperimentalRgbBackend.get(world);if(backend==null)return -1;
        var result=backend.columnSnapshot(target.getX()>>4,target.getZ()>>4);
        if(result==null || result.light()==null)return -1;
        var b=result.bounds();return result.light().get(target.getX()-b.x()+16*(target.getZ()-b.z()+16*(target.getY()-b.y())));
    }
    private static void save() {
        try {Files.createDirectories(path.getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(records));}
        catch(java.io.IOException failure){org.apache.logging.log4j.LogManager.getLogger("PCLA").error("Cannot save latency report",failure);}
    }
    public static void stop() {
        if(world==null)return;
        if(world.isBlockLoaded(source)&&world.getBlockState(source).getBlock()==Blocks.GLOWSTONE)world.setBlockToAir(source);
        save();world=null;source=null;current=null;
    }
}

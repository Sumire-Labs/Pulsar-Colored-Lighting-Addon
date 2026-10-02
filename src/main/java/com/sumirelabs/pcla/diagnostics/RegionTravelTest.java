package com.sumirelabs.pcla.diagnostics;

import com.google.gson.*;
import com.sumirelabs.pcla.light.world.ExperimentalRgbBackend;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.nio.file.*;
import java.util.*;

/** Opt-in real travel test: allows normal player-chunk unload, then returns and verifies fresh light. */
public final class RegionTravelTest {
    private static RegionTravelTest active;
    private final EntityPlayerMP player;
    private final ExperimentalRgbBackend backend;
    private final double x,y,z;
    private final float yaw,pitch;
    private final boolean flying;
    private final JsonObject report = new JsonObject();
    private Map<Long,Chunk> original;
    private int stage,ticks;
    private final Path output = Path.of("logs","pcla-travel-"+System.currentTimeMillis()+".json");

    private RegionTravelTest(EntityPlayerMP player, ExperimentalRgbBackend backend) {
        this.player=player; this.backend=backend;
        x=player.posX; y=player.posY; z=player.posZ; yaw=player.rotationYaw; pitch=player.rotationPitch;
        flying=player.capabilities.isFlying;
        report.addProperty("originalX",x); report.addProperty("originalY",y); report.addProperty("originalZ",z);
    }
    public static boolean running() { return active != null; }
    public static void start(EntityPlayerMP player, ExperimentalRgbBackend backend) throws java.io.IOException {
        if (active != null) throw new IllegalStateException("Travel test already running");
        if (!player.capabilities.isCreativeMode && !player.isSpectator()) throw new IllegalStateException("Travel test requires creative or spectator mode");
        if (!player.world.getWorldBorder().contains(new net.minecraft.util.math.BlockPos(player.posX+1024,player.posY,player.posZ)))
            throw new IllegalStateException("Test destination outside world border");
        var test=new RegionTravelTest(player,backend);
        test.report.addProperty("status","pending"); test.save();
        backend.watch((int)Math.floor(test.x),(int)Math.floor(test.y),(int)Math.floor(test.z),2);
        test.queue(); active=test; MinecraftForge.EVENT_BUS.register(test);
        player.sendMessage(new TextComponentString("PCLA travel test: temporarily moving 1024 blocks, then returning automatically."));
    }
    public static void stop() { if(active != null) active.finish("server_stopping"); }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if(event.phase != TickEvent.Phase.START) return;
        if (++ticks>1200) { finish("timeout_stage_"+stage); return; }
        if(player.connection == null || player.connection.netManager.isChannelOpen()==false) { finish("disconnected"); return; }
        try {
            if(stage==0) {
                if(backend.snapshot()==null) return;
                original=backend.dependencyChunks(0);
                if(original==null) return;
                var baseline=LiveRgbCommand.verify(backend);
                report.add("baseline",baseline);
                if(baseline.get("mismatches").getAsInt()!=0) throw new IllegalStateException("Baseline mismatch");
                player.capabilities.isFlying=true; player.sendPlayerAbilities();
                player.connection.setPlayerLocation(x+1024,y,z,yaw,pitch);
                stage=1; ticks=0;
            } else if(stage==1) {
                if(backend.dependencyChunks(0)!=null) return;
                if(backend.snapshot()!=null || backend.displaySnapshot(0)!=null) throw new IllegalStateException("Unloaded region still published for completion or display");
                report.addProperty("observedMissingHalo",true);
                player.connection.setPlayerLocation(x,y,z,yaw,pitch);
                stage=2; ticks=0; queue();
            } else {
                var chunks=backend.dependencyChunks(0);
                if(chunks==null || backend.snapshot()==null) return;
                long replaced=original.entrySet().stream().filter(e->chunks.get(e.getKey())!=e.getValue()).count();
                if(replaced==0) throw new IllegalStateException("No dependency identity changed after travel");
                var check=LiveRgbCommand.verify(backend); report.add("afterReload",check);
                report.addProperty("replacedChunks",replaced);
                if(check.get("mismatches").getAsInt()!=0) throw new IllegalStateException("Reload mismatch");
                finish("passed");
            }
        } catch(Exception e) { finish(e.toString()); }
    }
    private void queue() { ((PulsarWorld)player.world).pulsar$getLightManager().queueLightCheck(EnumSkyBlock.BLOCK,(int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z)); }
    private void save() throws java.io.IOException {
        Files.createDirectories(output.getParent()); Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    private void finish(String status) {
        MinecraftForge.EVENT_BUS.unregister(this); active=null;
        if(player.connection != null) {
            player.connection.setPlayerLocation(x,y,z,yaw,pitch);
            player.capabilities.isFlying=flying; player.sendPlayerAbilities();
        }
        report.addProperty("status",status);
        try { save(); } catch(java.io.IOException e) { status+="; report failed: "+e; }
        player.sendMessage(new TextComponentString("PCLA travel test "+status+"; "+output));
    }
}

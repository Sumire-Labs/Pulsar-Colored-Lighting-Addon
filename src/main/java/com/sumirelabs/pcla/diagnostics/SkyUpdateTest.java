package com.sumirelabs.pcla.diagnostics;

import com.google.gson.*;
import com.sumirelabs.pcla.light.world.ExperimentalRgbBackend;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.EnumSkyBlock;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.nio.file.*;

/** Disposable upper-column placement/removal and SKY-only request regression. */
public final class SkyUpdateTest {
    private static SkyUpdateTest active;
    private final EntityPlayerMP player;
    private final ExperimentalRgbBackend backend;
    private final Path output=Path.of("logs","pcla-sky-updates-"+System.currentTimeMillis()+".json");
    private final JsonObject report=new JsonObject();
    private final JsonArray phases=new JsonArray();
    private int[] baseline;
    private BlockPos probe;
    private int stage,ticks;
    private long previousRevision=-1;
    private SkyUpdateTest(EntityPlayerMP player,ExperimentalRgbBackend backend) {
        this.player=player; this.backend=backend; report.add("phases",phases);
        report.addProperty("blockLaneEnabled",backend.blockEnabled()); report.addProperty("skyLaneEnabled",backend.skyEnabled());
    }
    public static void start(EntityPlayerMP player,ExperimentalRgbBackend backend) throws java.io.IOException {
        if(active!=null) throw new IllegalStateException("Sky update test already running");
        ColorPreview.start(player,backend,false,false,true);
        active=new SkyUpdateTest(player,backend); MinecraftForge.EVENT_BUS.register(active);
    }
    public static void stop() { if(active!=null) active.finish("server_stopping"); }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.START) return;
        if(!ColorPreview.running()) { finish("preview_closed"); return; }
        if(++ticks>1200) { finish("timeout"); return; }
        var result=backend.snapshot();
        if(!ColorPreview.ready() || result==null || result.sky()==null || result.revision()<=previousRevision) return;
        try {
            var check=SkyVerification.verify(backend,0);
            if(check.get("mismatches").getAsInt()!=0) throw new IllegalStateException("Sky reference mismatch");
            check.addProperty("phase",switch(stage) { case 0 -> "baseline"; case 1 -> "upper_filter"; case 2 -> "restore_air"; default -> "sky_only_request"; });
            int count=result.sky().light().cellCount(),different=0;
            if(baseline==null) {
                baseline=new int[count]; for(int cell=0;cell<count;cell++) baseline[cell]=result.sky().light().get(cell);
                probe=new BlockPos(result.bounds().x()+3,result.bounds().inputTopY()+16,result.bounds().z()+4);
                report.addProperty("probe",probe.toString()); report.addProperty("inputTopY",result.bounds().inputTopY());
            } else for(int cell=0;cell<count;cell++) if(baseline[cell]!=result.sky().light().get(cell)) different++;
            check.addProperty("differentFromBaseline",different); phases.add(check);
            if(stage==1 && different==0) throw new IllegalStateException("Upper filter had no sky effect");
            if(stage>=2 && different!=0) throw new IllegalStateException("Original sky was not restored");
            if(stage==3) { finish("passed"); return; }
            previousRevision=result.revision(); ticks=0;
            if(stage==0) ColorPreview.changeSkyProbe(probe,Blocks.STAINED_GLASS.getDefaultState().withProperty(BlockStainedGlass.COLOR,EnumDyeColor.GREEN));
            else if(stage==1) ColorPreview.changeSkyProbe(probe,Blocks.AIR.getDefaultState());
            else {
                player.world.checkLightFor(EnumSkyBlock.SKY,probe);
                var after=backend.snapshot();
                boolean invalidated=after==null || after.revision()>previousRevision;
                report.addProperty("skyOnlyRequestInvalidated",invalidated);
                if(!invalidated) throw new IllegalStateException("SKY-only request did not invalidate the sidecar");
            }
            stage++; save();
        } catch(Exception e) { finish(e.toString()); }
    }
    private void save() throws java.io.IOException { Files.createDirectories(output.getParent()); Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
    private void finish(String status) {
        MinecraftForge.EVENT_BUS.unregister(this); active=null;
        report.addProperty("status",status); ColorPreview.stop();
        try { save(); } catch(java.io.IOException e) { status+="; report save failed: "+e; }
        player.sendMessage(new TextComponentString("PCLA sky update test "+status+"; "+output));
    }
}

package com.sumirelabs.pcla.diagnostics;

import com.google.gson.*;
import com.sumirelabs.pcla.light.world.ExperimentalRgbBackend;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.nio.file.*;
import java.util.*;

/** Explicit server-column diagnostics; does not enable automatic subscriptions or a new wire format. */
public final class ColumnRgbCommand extends CommandBase {
    private static Run active;
    public String getName() { return "pcla_column"; }
    public List<String> getAliases() { return List.of("pco"); }
    public String getUsage(ICommandSender sender) { return "/pco [select|verify|clear|test]"; }
    public int getRequiredPermissionLevel() { return 2; }
    public List<String> getTabCompletions(MinecraftServer server,ICommandSender sender,String[] args,BlockPos target) {
        return args.length==1?getListOfStringsMatchingLastWord(args,"select","verify","clear","test"):List.of();
    }
    public static boolean running() { return active!=null; }
    public static void stop() { if(active!=null) active.finish("server_stopping"); }
    public void execute(MinecraftServer server,ICommandSender sender,String[] args) throws CommandException {
        if(args.length!=1) throw new WrongUsageException(getUsage(sender));
        if(active!=null || ColorPreview.running() || RegionTravelTest.running() || LiveRgbCommand.runningTests()) throw new CommandException("Finish the active RGB diagnostic first");
        var player=getCommandSenderAsPlayer(sender); var backend=ExperimentalRgbBackend.get(player.world);
        if(backend==null) throw new CommandException("Enable experimentalWorldRgb first");
        int x=player.getPosition().getX()>>4,z=player.getPosition().getZ()>>4;
        try {
            switch(args[0]) {
                case "select" -> { backend.watchColumn(x,z); queue(player); say(player,"PCLA server column selected; chunk transport is not connected yet."); }
                case "verify" -> {
                    if(backend.blockEnabled()) say(player,LiveRgbCommand.verifyColumn(backend,x,z).toString());
                    if(backend.skyEnabled()) say(player,SkyVerification.verifyColumn(backend,x,z).toString());
                }
                case "clear" -> say(player,"PCLA column removed="+backend.removeColumn(x,z));
                case "test" -> {
                    if(!player.capabilities.isCreativeMode && !player.isSpectator()) throw new IllegalStateException("Use creative or spectator mode");
                    if(!backend.blockEnabled()) throw new IllegalStateException("Column update test requires the RGB BLOCK lane");
                    var run=new Run(player,backend,x,z); run.prepare();
                    active=run; MinecraftForge.EVENT_BUS.register(run);
                    try { queue(player); } catch(RuntimeException e) { run.finish("queue failed: "+e); throw e; }
                    say(player,"PCLA full-column test started; one recorded air cell will be changed and restored.");
                }
                default -> throw new WrongUsageException(getUsage(sender));
            }
        } catch(java.io.IOException|RuntimeException e) { throw new CommandException(e.toString()); }
    }
    private static void queue(EntityPlayerMP player) {
        var p=player.getPosition(); ((PulsarWorld)player.world).pulsar$getLightManager().queueLightCheck(net.minecraft.world.EnumSkyBlock.BLOCK,p.getX(),p.getY(),p.getZ());
    }
    private static void say(EntityPlayerMP player,String message) { player.sendMessage(new TextComponentString(message)); }
    private static final class Run {
        private static final String[] PHASES={"baseline","place_glowstone","replace_sea_lantern","replace_glass","restore_air"};
        final EntityPlayerMP player; final ExperimentalRgbBackend backend; final int x,z;
        final net.minecraft.world.World world;
        final Path output=Path.of("logs","pcla-column-"+System.currentTimeMillis()+".json");
        final JsonObject report=new JsonObject(); final JsonArray phases=new JsonArray();
        BlockPos position; IBlockState owned=Blocks.AIR.getDefaultState(),intended=owned; boolean removeOnFinish;
        int stage,ticks; long revision=-1; int[] baselineBlock,baselineSky;
        Run(EntityPlayerMP player,ExperimentalRgbBackend backend,int x,int z) { this.player=player;this.world=player.world;this.backend=backend;this.x=x;this.z=z; }
        void prepare() throws java.io.IOException {
            int baseY=player.getPosition().getY();
            var bounds=backend.columnBounds(x,z);
            outer: for(int dx:new int[]{16,8,-1}) for(int dy=-3;dy<=5;dy++) for(int dz=2;dz<14;dz++) {
                var candidate=new BlockPos(x*16+dx,baseY+dy,z*16+dz);
                if(candidate.getY()>=bounds.y() && candidate.getY()<bounds.y()+bounds.sizeY() && world.isBlockLoaded(candidate)
                        && world.getBlockState(candidate).getBlock()==Blocks.AIR && world.getTileEntity(candidate)==null) {
                    position=candidate; break outer;
                }
            }
            if(position==null) throw new IllegalStateException("No loaded air cell near the player for this test");
            report.addProperty("chunkX",x); report.addProperty("chunkZ",z); report.addProperty("testPosition",position.toString());
            report.addProperty("originalBlock","minecraft:air"); report.addProperty("blockLaneEnabled",backend.blockEnabled()); report.addProperty("skyLaneEnabled",backend.skyEnabled());
            report.addProperty("dimension",world.provider.getDimension());
            report.add("phases",phases); save("prepared");
            removeOnFinish=!backend.hasManualColumn(x,z); backend.watchColumn(x,z);
        }
        @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
            if(event.phase!=TickEvent.Phase.START) return;
            if(player.world!=world) { finish("player_changed_world"); return; }
            if(++ticks>1200) { finish("timeout; "+backend.columnFailure(x,z)); return; }
            var result=backend.columnSnapshot(x,z); if(result==null || result.revision()<=revision) return;
            try {
                if(!world.isBlockLoaded(position) || world.getBlockState(position)!=owned) throw new IllegalStateException("Test cell unavailable or changed externally");
                var check=LiveRgbCommand.verifyColumn(backend,x,z); check.addProperty("phase",PHASES[stage]);
                if(check.get("mismatches").getAsInt()!=0) throw new IllegalStateException("Column BLOCK mismatch");
                if(backend.skyEnabled()) {
                    var sky=SkyVerification.verifyColumn(backend,x,z); check.add("sky",sky);
                    if(sky.get("mismatches").getAsInt()!=0) throw new IllegalStateException("Column SKY mismatch");
                }
                if(backend.columnSnapshot(x,z)!=result) throw new IllegalStateException("Column changed during diagnostic");
                int blockDifference=0,skyDifference=0;
                if(baselineBlock==null) {
                    baselineBlock=new int[result.light().cellCount()];
                    if(result.sky()!=null) baselineSky=new int[result.sky().light().cellCount()];
                    for(int i=0;i<baselineBlock.length;i++) { baselineBlock[i]=result.light().get(i); if(baselineSky!=null) baselineSky[i]=result.sky().light().get(i); }
                    report.addProperty("bounds",result.bounds().toString()); report.addProperty("outputSections",result.bounds().sectionsY());
                } else for(int i=0;i<baselineBlock.length;i++) {
                    if(baselineBlock[i]!=result.light().get(i)) blockDifference++;
                    if(baselineSky!=null && baselineSky[i]!=result.sky().light().get(i)) skyDifference++;
                }
                check.addProperty("blockDifferentFromBaseline",blockDifference); check.addProperty("skyDifferentFromBaseline",skyDifference); phases.add(check);
                if(stage==4) { if(blockDifference!=0 || skyDifference!=0) throw new IllegalStateException("Column did not return to baseline"); finish("passed"); return; }
                if(stage==1 && blockDifference==0) throw new IllegalStateException("Test source had no effect in the owned chunk");
                revision=result.revision(); ticks=0;
                IBlockState next=switch(++stage) { case 1->Blocks.GLOWSTONE.getDefaultState(); case 2->Blocks.SEA_LANTERN.getDefaultState(); case 3->Blocks.STAINED_GLASS.getDefaultState(); default->Blocks.AIR.getDefaultState(); };
                intended=next;
                save("running");
                if(!world.setBlockState(position,next,2)) throw new IllegalStateException("Column test edit rejected");
                owned=next; save("running");
            } catch(Exception e) { finish(e.toString()); }
        }
        void save(String status) throws java.io.IOException {
            report.addProperty("status",status); report.addProperty("ownedBlock",String.valueOf(owned.getBlock().getRegistryName()));
            report.addProperty("intendedBlock",String.valueOf(intended.getBlock().getRegistryName()));
            Files.createDirectories(output.getParent()); Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
        void finish(String status) {
            MinecraftForge.EVENT_BUS.unregister(this); active=null;
            boolean restored=false;
            if(position!=null && world.isBlockLoaded(position)) {
                var current=world.getBlockState(position);
                if(current==owned || current==intended || current.getBlock()==Blocks.AIR) {
                    world.setBlockState(position,Blocks.AIR.getDefaultState(),2); restored=world.getBlockState(position).getBlock()==Blocks.AIR;
                }
            }
            if(restored) owned=intended=Blocks.AIR.getDefaultState();
            report.addProperty("restoredAir",restored);
            if(removeOnFinish) report.addProperty("columnRemoved",backend.removeColumn(x,z));
            try { save(status); } catch(java.io.IOException e) { status+="; save failed: "+e; }
            say(player,"PCLA full-column test "+status+"; "+output);
        }
    }
}

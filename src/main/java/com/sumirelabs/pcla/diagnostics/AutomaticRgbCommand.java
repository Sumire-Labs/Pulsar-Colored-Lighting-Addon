package com.sumirelabs.pcla.diagnostics;

import com.google.gson.*;
import com.sumirelabs.pcla.config.RuntimeOptions;
import com.sumirelabs.pcla.light.world.*;
import com.sumirelabs.pcla.network.*;
import com.sumirelabs.pulsar.world.PulsarWorld;
import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import java.nio.file.*;
import java.util.*;

/** Diagnostics for the actual automatic mode. Normal tracking never calls this command. */
public final class AutomaticRgbCommand extends CommandBase {
    private static Travel active;
    public String getName(){return "pcla_auto";}
    public List<String> getAliases(){return List.of("pca");}
    public String getUsage(ICommandSender sender){return "/pca [status|verify|travel|far|pulse|return]";}
    public int getRequiredPermissionLevel(){return 2;}
    public List<String> getTabCompletions(MinecraftServer server,ICommandSender sender,String[] args,BlockPos target){return args.length==1?getListOfStringsMatchingLastWord(args,"status","verify","travel","far","pulse","return"):List.of();}
    public static void stop(){if(active!=null)active.finish();}
    public void execute(MinecraftServer server,ICommandSender sender,String[] args)throws CommandException {
        if(args.length!=1)throw new WrongUsageException(getUsage(sender));
        var player=getCommandSenderAsPlayer(sender);var backend=ExperimentalRgbBackend.get(player.world);
        if(!RuntimeOptions.AUTOMATIC || backend==null)throw new CommandException("Enable automaticWorldRgb first");
        try {
            switch(args[0]) {
                case "status","verify" -> report(player,backend,args[0].equals("verify"));
                case "travel","far" -> {
                    if(active!=null || ColorPreview.running() || LiveRgbCommand.runningTests() || ColumnRgbCommand.running() || RegionTravelTest.running())throw new IllegalStateException("Finish the active RGB test first");
                    if(!player.capabilities.isCreativeMode)throw new IllegalStateException("Travel diagnostic requires creative mode");
                    var travel=new Travel(player,backend);travel.prepare(args[0].equals("far"));active=travel;
                    try {travel.move();}catch(RuntimeException failure){travel.finish();throw failure;}
                    say(player,"PCLA automatic travel: moved beyond the old RGB window. Wait for lighting, then use /pca verify and /pcc; /pca return restores the marker, clock and position.");
                }
                case "return" -> {if(active==null)throw new IllegalStateException("No travel diagnostic is active");if(active.player!=player)throw new IllegalStateException("Another player owns the diagnostic");active.finish();}
                case "pulse" -> {
                    if(active==null || active.player!=player || active.marker==null || player.world!=active.world || !active.world.isBlockLoaded(active.marker))throw new IllegalStateException("A loaded near-travel marker is required");
                    var block=active.world.getBlockState(active.marker).getBlock();
                    if(block!=Blocks.AIR && block!=Blocks.GLOWSTONE)throw new IllegalStateException("Marker was externally changed");
                    boolean on=block==Blocks.AIR;
                    active.world.setBlockState(active.marker,(on?Blocks.GLOWSTONE:Blocks.AIR).getDefaultState(),2);
                    say(player,"PCLA travel marker="+(on?"glowstone":"air")+"; normal automatic updates will propagate and stream the change.");
                }
                default -> throw new WrongUsageException(getUsage(sender));
            }
        }catch(java.io.IOException|RuntimeException e){throw new CommandException(e.toString());}
    }
    private static void report(EntityPlayerMP player,ExperimentalRgbBackend backend,boolean verify)throws java.io.IOException {
        int x=player.getPosition().getX()>>4,z=player.getPosition().getZ()>>4;
        var report=new JsonObject();report.addProperty("automatic",true);report.addProperty("radius",RuntimeOptions.automaticRadius());report.addProperty("dimension",player.dimension);
        report.addProperty("centerX",x);report.addProperty("centerZ",z);report.addProperty("ownersInWorld",backend.automaticColumns().size());
        report.add("transfer",new Gson().toJsonTree(ServerRgbSync.transferStats()));
        report.add("workerCache",new Gson().toJsonTree(backend.workerCacheStats()));
        report.add("pipeline",new Gson().toJsonTree(backend.pipelineStats()));
        var targets=new JsonArray();report.add("targets",targets);
        for(long key:ServerRgbSync.automaticTargets(player)) {
            int cx=(int)key,cz=(int)(key>>32);var item=new JsonObject();item.addProperty("x",cx);item.addProperty("z",cz);
            var result=backend.columnSnapshot(cx,cz);item.addProperty("complete",result!=null);
            if(result!=null) {
                var frame=RegionFrame.upsertV3(UUID.randomUUID(),1,1,player.dimension,4,result.bounds(),copy(result.light()),copy(result.sky()==null?null:result.sky().light()));
                item.addProperty("blockDigest",FrameDigest.sha256(frame));item.addProperty("skyDigest",FrameDigest.skySha256(frame));item.addProperty("cells",frame.cells());
            }
            targets.add(item);
        }
        if(verify){if(backend.blockEnabled())report.add("blockReference",LiveRgbCommand.verifyColumn(backend,x,z));if(backend.skyEnabled())report.add("skyReference",SkyVerification.verifyColumn(backend,x,z));}
        Path output=Path.of("logs","pcla-auto-"+System.currentTimeMillis()+".json");save(output,report);
        say(player,"PCLA automatic targets="+targets.size()+"; "+output);
    }
    private static short[] copy(com.sumirelabs.pcla.light.storage.RgbLightVolume.Snapshot root){if(root==null)return null;short[] data=new short[root.cellCount()];root.copyTo(data);return data;}
    private static void save(Path path,JsonObject value)throws java.io.IOException {Files.createDirectories(path.getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(value));}
    private static void say(EntityPlayerMP player,String value){player.sendMessage(new TextComponentString(value));}
    private static final class Travel {
        final EntityPlayerMP player;final net.minecraft.world.World world;final ExperimentalRgbBackend backend;
        final double x,y,z;final float yaw,pitch;final boolean flying;
        final long time,totalTime;final String daylight;
        final Path path=Path.of("logs","pcla-auto-travel-"+System.currentTimeMillis()+".json");final JsonObject record=new JsonObject();
        BlockPos destination,marker;boolean clockChanged;
        Travel(EntityPlayerMP player,ExperimentalRgbBackend backend){this.player=player;world=player.world;this.backend=backend;x=player.posX;y=player.posY;z=player.posZ;yaw=player.rotationYaw;pitch=player.rotationPitch;flying=player.capabilities.isFlying;time=world.getWorldTime();totalTime=world.getTotalWorldTime();daylight=world.getGameRules().getString("doDaylightCycle");}
        void prepare(boolean far)throws java.io.IOException {
            int cx=player.getPosition().getX()>>4,cz=player.getPosition().getZ()>>4,distance=2*RuntimeOptions.automaticRadius()+1;
            var manager=((PulsarWorld)world).pulsar$getLightManager();
            if(far) {
                for(int offset:new int[]{24,-24,48,-48,96,-96}) {
                    int targetX=cx+offset;
                    var candidate=new BlockPos(targetX*16+8,255,cz*16+8);
                    if(manager.getLoadedChunk(targetX,cz)==null && world.getWorldBorder().contains(candidate)) {
                        destination=candidate;record.addProperty("destinationInitiallyLoaded",false);break;
                    }
                }
            } else for(int direction:new int[]{1,-1}) {
                int targetX=cx+direction*distance;var chunk=manager.getLoadedChunk(targetX,cz);if(chunk==null)continue;
                boolean loaded=true;for(long key:backend.columnBounds(targetX,cz).chunks())if(manager.getLoadedChunk((int)key,(int)(key>>32))==null){loaded=false;break;}if(!loaded)continue;
                var bounds=backend.columnBounds(targetX,cz);int height=Math.max(bounds.y()+2,Math.min(bounds.y()+bounds.sizeY()-4,chunk.getHeightValue(8,8)+3));
                var candidate=new BlockPos(targetX*16+10,height,cz*16+8);
                if(world.getBlockState(candidate).getBlock()!=Blocks.AIR || world.getTileEntity(candidate)!=null)continue;
                destination=new BlockPos(targetX*16+8,height,cz*16+8);marker=candidate;record.addProperty("destinationInitiallyLoaded",true);break;
            }
            if(destination==null)throw new IllegalStateException("No suitable travel destination beyond the current RGB window");
            record.addProperty("originalX",x);record.addProperty("originalY",y);record.addProperty("originalZ",z);record.addProperty("originalYaw",yaw);record.addProperty("originalPitch",pitch);
            record.addProperty("destination",destination.toString());record.addProperty("marker",marker==null?null:marker.toString());record.addProperty("originalMarker",marker==null?null:"minecraft:air");record.addProperty("originalTime",time);record.addProperty("originalDaylightCycle",daylight);record.addProperty("status","prepared");save(path,record);
        }
        void move() {
            if(marker!=null && !world.setBlockState(marker,Blocks.GLOWSTONE.getDefaultState(),2))throw new IllegalStateException("Marker placement failed");
            world.getGameRules().setOrCreateGameRule("doDaylightCycle","false");world.setWorldTime(18000);clockChanged=true;
            player.capabilities.isFlying=true;player.sendPlayerAbilities();player.connection.setPlayerLocation(destination.getX()+0.5,destination.getY(),destination.getZ()+0.5,-90,20);
        }
        void finish() {
            active=null;boolean restored=marker==null;
            if(marker!=null && world.isBlockLoaded(marker)) {
                var block=world.getBlockState(marker).getBlock();if(block==Blocks.GLOWSTONE || block==Blocks.AIR){world.setBlockState(marker,Blocks.AIR.getDefaultState(),2);restored=world.getBlockState(marker).getBlock()==Blocks.AIR;}
            }
            boolean clockRestored=!clockChanged;
            if(clockChanged && world.getWorldTime()==18000 && world.getGameRules().getString("doDaylightCycle").equals("false")) {
                world.setWorldTime(time+(daylight.equals("true")?world.getTotalWorldTime()-totalTime:0));world.getGameRules().setOrCreateGameRule("doDaylightCycle",daylight);clockRestored=true;
            }
            if(player.world==world){player.connection.setPlayerLocation(x,y,z,yaw,pitch);player.capabilities.isFlying=flying;player.sendPlayerAbilities();}
            record.addProperty("status","finished");record.addProperty("markerRestored",restored);record.addProperty("clockRestored",clockRestored);record.addProperty("returnedToStart",player.world==world);
            try{save(path,record);}catch(java.io.IOException error){say(player,"Cannot save travel recovery report: "+error);}
            say(player,"PCLA automatic travel restored marker="+restored+", clock="+clockRestored+"; "+path);
        }
    }
}

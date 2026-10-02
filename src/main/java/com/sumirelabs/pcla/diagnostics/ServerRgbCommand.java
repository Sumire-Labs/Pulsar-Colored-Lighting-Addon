package com.sumirelabs.pcla.diagnostics;

import com.google.gson.*;
import com.sumirelabs.pcla.Pcla;
import com.sumirelabs.pcla.light.world.ExperimentalRgbBackend;
import com.sumirelabs.pcla.network.ServerRgbSync;
import net.minecraft.command.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import java.nio.file.*;
import java.util.*;

/** Dedicated-server lifecycle inspection, plus bounded existing diagnostics for an online test player. */
public final class ServerRgbCommand extends CommandBase {
    public String getName(){return "pcla_server";}
    public List<String> getAliases(){return List.of("pcs");}
    public String getUsage(ICommandSender sender){return "/pcs [probe|player <name> status|verify|travel|far|pulse|return]";}
    public int getRequiredPermissionLevel(){return 2;}
    public void execute(MinecraftServer server,ICommandSender sender,String[] args)throws CommandException {
        if(args.length==7 && args[0].equals("timedtp")) {
            if(!Boolean.getBoolean("pcla.networkDiagnostics") || sender!=server)throw new CommandException("Timed travel requires console diagnostics");
            var player=server.getPlayerList().getPlayerByUsername(args[1]);if(player==null)throw new CommandException("Player not found");
            double x=parseDouble(args[2]),y=parseDouble(args[3]),z=parseDouble(args[4]);
            float yaw=(float)parseDouble(args[5]),pitch=(float)parseDouble(args[6]);
            var report=new JsonObject();report.addProperty("type","teleport");report.addProperty("timeMs",System.currentTimeMillis());
            report.addProperty("x",x);report.addProperty("y",y);report.addProperty("z",z);report.addProperty("yaw",yaw);report.addProperty("pitch",pitch);
            player.connection.setPlayerLocation(x,y,z,yaw,pitch);
            var path=Path.of("logs","pcla-timed-travel-"+System.currentTimeMillis()+".json");
            try{Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report));}
            catch(java.io.IOException e){throw new CommandException(e.toString());}
            sender.sendMessage(new TextComponentString("PCLA timed travel: "+path));return;
        }
        if(args.length==4 && args[0].equals("latency")) {
            if(!Boolean.getBoolean("pcla.networkDiagnostics") || sender!=server)throw new CommandException("Latency sequence requires console diagnostics");
            try{LightingLatencySequence.start(server.getWorld(0),new net.minecraft.util.math.BlockPos(parseInt(args[1]),parseInt(args[2]),parseInt(args[3])));}
            catch(RuntimeException e){throw new CommandException(e.toString());}
            sender.sendMessage(new TextComponentString("PCLA latency: 20 source changes, one every 60 ticks; restores source."));return;
        }
        if(args.length==4 && args[0].equals("sample")) {
            if(!Boolean.getBoolean("pcla.networkDiagnostics") || sender!=server)throw new CommandException("Input sampling requires console diagnostics");
            int x=parseInt(args[1]),y=parseInt(args[2]),z=parseInt(args[3]);
            var world=server.getWorld(0);var backend=ExperimentalRgbBackend.get(world);
            if(backend==null)throw new CommandException("RGB backend unavailable");
            var report=new JsonObject();report.addProperty("x",x);report.addProperty("y",y);report.addProperty("z",z);
            report.add("input",new Gson().toJsonTree(backend.sampleInput(x,y,z)));
            var pos=new net.minecraft.util.math.BlockPos(x,y,z);
            report.addProperty("hostState",world.getBlockState(pos).toString());
            report.addProperty("scalarBlock",world.getLightFor(net.minecraft.world.EnumSkyBlock.BLOCK,pos));
            report.addProperty("scalarSky",world.getLightFor(net.minecraft.world.EnumSkyBlock.SKY,pos));
            var loaded=((com.sumirelabs.pulsar.api.ExtendedWorld)world).pulsar$getAnyChunkImmediately(x>>4,z>>4);
            if(loaded instanceof com.sumirelabs.pulsar.light.PulsarChunk lightChunk) {
                report.addProperty("scalarSavedLightValid",lightChunk.pulsar$hasSavedLightValid());
                report.addProperty("scalarLightReady",lightChunk.pulsar$isLightReady());
            }
            var result=backend.columnSnapshot(x>>4,z>>4);report.addProperty("complete",result!=null);
            if(result!=null) {
                var b=result.bounds();int cell=x-b.x()+b.sizeX()*(z-b.z()+b.sizeZ()*(y-b.y()));
                report.addProperty("rgbBlock",result.light()==null?-1:result.light().get(cell));
                report.addProperty("rgbSky",result.sky()==null?-1:result.sky().light().get(cell));
            }
            var path=Path.of("logs","pcla-input-"+System.currentTimeMillis()+".json");
            try{Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report));}
            catch(java.io.IOException e){throw new CommandException(e.toString());}
            sender.sendMessage(new TextComponentString("PCLA input sample: "+path));return;
        }
        if(args.length==3 && args[0].equals("scene")) {
            if(!Boolean.getBoolean("pcla.networkDiagnostics") || sender!=server)throw new CommandException("Scene diagnostics require the server console and networkDiagnostics");
            if(!Set.of("preview","shapes","entities","sky","fluids","colors","clearpreview").contains(args[2]))throw new WrongUsageException("/pcs scene <player> preview|shapes|entities|sky|fluids|colors|clearpreview");
            var player=server.getPlayerList().getPlayerByUsername(args[1]);
            if(player==null)throw new CommandException("Test player is not online");
            new LiveRgbCommand().execute(server,player,new String[]{args[2]});return;
        }
        if(args.length==1 && args[0].equals("probe")) {
            if(!Boolean.getBoolean("pcla.networkDiagnostics") || sender!=server)throw new CommandException("Probe requires the server console and networkDiagnostics");
            var world=server.getWorld(0);var pos=world.getSpawnPoint().up(32);
            if(!world.isBlockLoaded(pos))throw new CommandException("Spawn probe chunk is not loaded");
            var located=new CommandSenderWrapper(sender,new net.minecraft.util.math.Vec3d(pos),pos,2,null,true);
            new LiveRgbCommand().execute(server,located,new String[]{"testmulti"});return;
        }
        if(args.length==3 && args[0].equals("player")) {
            if(!Boolean.getBoolean("pcla.networkDiagnostics"))throw new CommandException("Player diagnostics require networkDiagnostics");
            if(!Set.of("status","verify","travel","far","pulse","return").contains(args[2]))throw new WrongUsageException(getUsage(sender));
            var player=server.getPlayerList().getPlayerByUsername(args[1]);
            if(player==null)throw new CommandException("Test player is not online");
            new AutomaticRgbCommand().execute(server,player,new String[]{args[2]});return;
        }
        if(args.length!=0)throw new WrongUsageException(getUsage(sender));
        var gson=new GsonBuilder().setPrettyPrinting().create();var report=new JsonObject();
        report.addProperty("dedicated",server.isDedicatedServer());report.addProperty("players",server.getCurrentPlayerCount());
        long[] ticks=server.tickTimeArray.clone();java.util.Arrays.sort(ticks);
        report.addProperty("recentTickMedianMs",ticks[ticks.length/2]/1e6);
        report.addProperty("recentTickP95Ms",ticks[(int)((ticks.length-1)*0.95)]/1e6);
        report.addProperty("recentTickMaxMs",ticks[ticks.length-1]/1e6);
        var heap=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        report.addProperty("heapUsedBytes",heap.getUsed());report.addProperty("heapCommittedBytes",heap.getCommitted());
        var threads=java.lang.management.ManagementFactory.getThreadMXBean();
        if(threads instanceof com.sun.management.ThreadMXBean ext && ext.isThreadAllocatedMemorySupported() && ext.isThreadAllocatedMemoryEnabled())
            report.addProperty("serverThreadAllocatedBytes",ext.getThreadAllocatedBytes(Thread.currentThread().threadId()));
        report.addProperty("simulationProfile",Pcla.startupConfig().simulationProfile());
        report.add("lifecycle",gson.toJsonTree(ServerRgbSync.lifecycleStats()));report.add("transfer",gson.toJsonTree(ServerRgbSync.transferStats()));
        var worlds=new JsonArray();report.add("worlds",worlds);
        for(var world:net.minecraftforge.common.DimensionManager.getWorlds()) {
            var item=new JsonObject();item.addProperty("dimension",world.provider.getDimension());
            item.addProperty("spawn",world.getSpawnPoint().toString());
            var backend=ExperimentalRgbBackend.get(world);item.addProperty("rgbBackend",backend!=null);
            if(backend!=null) {
                item.addProperty("automaticOwners",backend.automaticColumns().size());item.addProperty("manualOwners",backend.slots().size());
                item.add("workerCache",gson.toJsonTree(backend.workerCacheStats()));item.add("pipeline",gson.toJsonTree(backend.pipelineStats()));
                item.add("sharedInputs",gson.toJsonTree(backend.sharedInputStats()));
                item.add("noopChecks",gson.toJsonTree(backend.noopStats()));
                item.add("sharedChunks",gson.toJsonTree(backend.sharedChunkStats()));
                item.add("rgbWorker",gson.toJsonTree(backend.rgbWorkerStats()));
            }
            worlds.add(item);
        }
        var path=Path.of("logs","pcla-server-"+System.currentTimeMillis()+".json");
        try{Files.createDirectories(path.getParent());Files.writeString(path,gson.toJson(report));}
        catch(java.io.IOException e){throw new CommandException("Cannot save server diagnostic: "+e);}
        sender.sendMessage(new TextComponentString("PCLA server players="+server.getCurrentPlayerCount()+"; "+path));
    }
}

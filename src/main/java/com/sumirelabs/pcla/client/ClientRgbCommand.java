package com.sumirelabs.pcla.client;

import com.google.gson.*;
import com.sumirelabs.pcla.network.FrameDigest;
import net.minecraft.command.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import java.nio.file.*;

/** Read-only client diagnostic of data actually received through the network. */
public final class ClientRgbCommand extends CommandBase {
    public String getName() { return "pcla_client"; }
    public java.util.List<String> getAliases() { return java.util.List.of("pcc"); }
    public String getUsage(ICommandSender sender) { return "/pcla_client [on|off|flat|smooth|entityon|entityoff|skyon|skyoff|fluidprobe|gputest|profile|terrainprobe|latency|latencystop]"; }
    public java.util.List<String> getTabCompletions(MinecraftServer server,ICommandSender sender,String[] args,net.minecraft.util.math.BlockPos target) {
        return args.length==1 ? getListOfStringsMatchingLastWord(args,"on","off","flat","smooth","entityon","entityoff","skyon","skyoff","fluidprobe","gputest","profile","terrainprobe","latency","latencystop") : java.util.List.of();
    }
    public int getRequiredPermissionLevel() { return 0; }
    public void execute(MinecraftServer server,ICommandSender sender,String[] args) throws CommandException {
        if(args.length==1 && args[0].equals("latency")) {
            try{VisualLatencyProbe.start();}catch(IllegalStateException e){throw new CommandException(e.getMessage());}
            sender.sendMessage(new TextComponentString("PCLA pixel observation started for 120 seconds; keep camera unchanged."));return;
        }
        if(args.length==1 && args[0].equals("latencystop")){VisualLatencyProbe.close();return;}
        if(args.length==1 && args[0].equals("terrainprobe")) {
            if(!Boolean.getBoolean("pcla.networkDiagnostics"))throw new CommandException("Terrain probe requires networkDiagnostics");
            try {
                var report=TerrainLightProbe.run();var path=Path.of("logs","pcla-terrain-"+System.currentTimeMillis()+".json");
                Files.createDirectories(path.getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report));
                sender.sendMessage(new TextComponentString("PCLA terrain report saved: "+path));
            }catch(Exception e){throw new CommandException("Terrain probe failed: "+e);}return;
        }
        if(args.length==1 && args[0].equals("handprobe")) {
            if(!Boolean.getBoolean("pcla.networkDiagnostics"))throw new CommandException("Hand probe requires networkDiagnostics");
            RenderStateProbe.request();sender.sendMessage(new TextComponentString("PCLA will record the next hand frame."));return;
        }
        if(args.length==1 && args[0].equals("profile")) {
            try{RenderPerformanceProbe.start();}catch(IllegalStateException e){throw new CommandException(e.getMessage());}
            sender.sendMessage(new TextComponentString("PCLA render probe: 5 seconds warmup, then 30 seconds. Keep the view unchanged."));return;
        }
        if(args.length==1 && args[0].equals("gputest")) {
            if(!Boolean.getBoolean("pcla.networkDiagnostics"))throw new CommandException("GPU probe requires networkDiagnostics");
            try {
                var report=GpuLightmapProbe.run();var path=Path.of("logs","pcla-gpu-"+System.currentTimeMillis()+".json");
                Files.createDirectories(path.getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report));
                int mismatches=report.get("mismatchesOverTwo").getAsInt()+report.get("nativeMismatchesOverOne").getAsInt()
                    +report.get("hurtMismatchesOverTwo").getAsInt()+report.get("entityMismatchesOverTwo").getAsInt()
                    +report.get("rawStageMismatchesOverTwo").getAsInt();
                sender.sendMessage(new TextComponentString("PCLA GPU mismatches="+mismatches+", GL error="+report.get("glError")+"; "+path));
            } catch(Exception e){throw new CommandException("RGB GPU probe failed: "+e);}
            return;
        }
        if(args.length==1 && args[0].equals("fluidprobe")) {
            var mc=net.minecraft.client.Minecraft.getMinecraft();
            if(!Boolean.getBoolean("pcla.networkDiagnostics") || mc.player==null)throw new CommandException("Fluid probes require a world and networkDiagnostics");
            FluidTint.probe(mc.player.getPosition());mc.renderGlobal.loadRenderers();
            sender.sendMessage(new TextComponentString("PCLA records actual fluid-renderer samples in this section; /pcc saves them."));return;
        }
        if(args.length==1 && args[0].equals("forget")) {
            if(!Boolean.getBoolean("pcla.networkDiagnostics"))throw new CommandException("Baseline fault injection requires networkDiagnostics");
            sender.sendMessage(new TextComponentString("PCLA discarded current column baseline="+ClientProxy.forgetCurrentColumn()+"; the next delta will request a full resync."));return;
        }
        if(args.length==1 && (args[0].equals("skyon") || args[0].equals("skyoff"))) {
            if(!SkyTint.INSTALLED || !BlockTint.INSTALLED && !EntityTint.INSTALLED)
                throw new CommandException("Enable experimentalSkyTint and a block/entity tint hook first");
            SkyTint.enabled=args[0].equals("skyon");
            if(BlockTint.INSTALLED) net.minecraft.client.Minecraft.getMinecraft().renderGlobal.loadRenderers();
            sender.sendMessage(new TextComponentString("PCLA experimental sky tint="+SkyTint.enabled)); return;
        }
        if(args.length==1 && (args[0].equals("entityon") || args[0].equals("entityoff"))) {
            if(!EntityTint.INSTALLED) throw new CommandException("Start with -Dpcla.experimentalEntityTint=true first");
            EntityTint.enabled=args[0].equals("entityon");
            sender.sendMessage(new TextComponentString("PCLA experimental entity tint="+EntityTint.enabled)); return;
        }
        if(args.length==1 && java.util.Set.of("on","off","flat","smooth").contains(args[0])) {
            if(!BlockTint.INSTALLED) throw new CommandException("Start with -Dpcla.experimentalBlockTint=true first");
            if(args[0].equals("on") || args[0].equals("off")) BlockTint.enabled=args[0].equals("on");
            else BlockTint.smooth=args[0].equals("smooth");
            net.minecraft.client.Minecraft.getMinecraft().renderGlobal.loadRenderers();
            sender.sendMessage(new TextComponentString("PCLA experimental block tint="+BlockTint.enabled+", smooth="+BlockTint.smooth)); return;
        }
        if(args.length!=0) throw new WrongUsageException(getUsage(sender));
        var snapshot=ClientProxy.STORE.snapshot();
        if(snapshot==null) { sender.sendMessage(new TextComponentString("PCLA client RGB has no active server session.")); return; }
        var report=new JsonObject();
        report.addProperty("automaticMode",com.sumirelabs.pcla.config.RuntimeOptions.AUTOMATIC);
        report.addProperty("nativeLightmap",ClientLightmap.snapshot()!=null);
        report.addProperty("gpuLightmapRequested",RgbGpuLightmap.REQUESTED);
        report.addProperty("gpuLightmapReady",RgbGpuLightmap.ready());
        report.addProperty("gpuLightmapFailure",RgbGpuLightmap.failure);
        report.addProperty("gpuLightmapUploads",RgbGpuLightmap.uploads);
        report.addProperty("gpuTerrainPasses",RgbGpuLightmap.terrainPasses);
        report.addProperty("gpuQuads",BlockTint.gpuQuads.sum());
        report.addProperty("gpuEntityPasses",RgbGpuLightmap.entityPasses);
        report.addProperty("gpuPaintingVertices",GpuPaintingLight.writtenVertices);
        var gpuCurve=RgbGpuLightmap.curve();
        if(gpuCurve!=null && Boolean.getBoolean("pcla.networkDiagnostics")) {
            report.addProperty("gpuGamma",gpuCurve.gamma());report.addProperty("gpuNightVision",gpuCurve.night());
            report.add("gpuRawLightmap",new Gson().toJsonTree(gpuCurve.raw()));
        }
        report.addProperty("lightmapPublications",ClientLightmap.publications);
        report.add("lightmapKey",new Gson().toJsonTree(ClientLightmap.key()));
        var displayMap=ClientLightmap.snapshot();
        if(displayMap!=null && Boolean.getBoolean("pcla.networkDiagnostics"))report.add("lightmapColors",new Gson().toJsonTree(displayMap.copyColors()));
        var localPlayer=net.minecraft.client.Minecraft.getMinecraft().player;
        if(localPlayer!=null){report.addProperty("playerX",localPlayer.posX);report.addProperty("playerY",localPlayer.posY);report.addProperty("playerZ",localPlayer.posZ);report.addProperty("centerChunkX",localPlayer.getPosition().getX()>>4);report.addProperty("centerChunkZ",localPlayer.getPosition().getZ()>>4);}
        report.addProperty("experimentalBlockTint",BlockTint.enabled);
        report.addProperty("experimentalSkyTint",SkyTint.enabled); report.addProperty("displayDaylight",BlockTint.daylight);
        report.addProperty("tintedQuads",BlockTint.tintedQuads.sum());
        report.addProperty("experimentalCeleritasTint",com.sumirelabs.pcla.config.RuntimeOptions.celeritasTint());
        report.addProperty("celeritasQuads",BlockTint.celeritasQuads.sum());
        report.addProperty("waterVertices",FluidTint.waterVertices.sum());report.addProperty("lavaVertices",FluidTint.lavaVertices.sum());
        report.addProperty("tintedFluidVertices",FluidTint.tintedVertices.sum());report.addProperty("celeritasFluidVertices",FluidTint.celeritasVertices.sum());
        report.addProperty("excludedFluidRenders",FluidTint.excludedRenders.sum());report.addProperty("fluidScopeDepth",FluidTint.depth());
        report.add("fluidSamples",new Gson().toJsonTree(FluidTint.probes()));
        report.addProperty("smooth",BlockTint.smooth); report.addProperty("smoothedQuads",BlockTint.smoothedQuads.sum());
        report.addProperty("insetQuads",BlockTint.insetQuads.sum());
        report.addProperty("entityTint",EntityTint.enabled); report.addProperty("tintedEntityRenders",EntityTint.tintedRenders.sum());
        report.addProperty("paintingTiles",EntityTint.paintingTiles.sum()); report.addProperty("entityScopeDepth",EntityTint.state().depth());
        var samples=new JsonArray(); report.add("entitySamples",samples);
        var world=net.minecraft.client.Minecraft.getMinecraft().world;
        if(world!=null) for(var entity:world.loadedEntityList) {
            var pos=new net.minecraft.util.math.BlockPos(entity.posX,entity.posY+entity.getEyeHeight(),entity.posZ);
            int rgb=snapshot.sample(pos.getX(),pos.getY(),pos.getZ()),skyRgb=snapshot.sampleSky(pos.getX(),pos.getY(),pos.getZ()); if(rgb<0 && skyRgb<0) continue;
            var sample=new JsonObject(); sample.addProperty("type",String.valueOf(net.minecraft.entity.EntityList.getKey(entity)));
            sample.addProperty("uuid",entity.getUniqueID().toString()); sample.addProperty("entityId",entity.getEntityId());
            boolean tracked=false;
            if(world.isBlockLoaded(pos)) for(var section:world.getChunk(pos).getEntityLists()) if(section.contains(entity)) { tracked=true; break; }
            sample.addProperty("inChunkEntityList",tracked);
            sample.addProperty("name",entity.getName()); sample.addProperty("x",pos.getX()); sample.addProperty("y",pos.getY()); sample.addProperty("z",pos.getZ());
            int light=world.getCombinedLight(pos,0); sample.addProperty("block",light>>4&15); sample.addProperty("sky",light>>20&15);
            sample.addProperty("brightnessForRender",entity.getBrightnessForRender()); sample.addProperty("rgb",rgb);
            sample.addProperty("skyRgb",skyRgb);
            samples.add(sample);
        }
        report.addProperty("session",snapshot.session().toString()); report.addProperty("epoch",snapshot.epoch());
        report.addProperty("sequence",snapshot.sequence()); report.addProperty("dimension",snapshot.dimension());
        report.addProperty("protocol",snapshot.protocol());
        report.addProperty("deltasApplied",ClientProxy.deltasApplied);report.addProperty("resyncRequests",ClientProxy.resyncRequests);
        report.addProperty("indexedChunks",snapshot.index().chunks()); report.addProperty("indexedSections",snapshot.index().sections());
        report.addProperty("serverProfile",snapshot.profile());
        var settings=ClientDisplaySettings.active();
        if(ClientDisplaySettings.matches(snapshot,settings)) {
            var details=new JsonObject();report.add("settings",details);
            var local=ClientDisplaySettings.local();var remote=settings.policy().simulation();
            details.addProperty("synchronized",settings.synchronizedSettings());
            details.addProperty("serverSettingsProfile",settings.synchronizedSettings()?remote.settingsProfile():null);details.addProperty("localSettingsProfile",local.settingsProfile());
            details.addProperty("serverSimulationProfile",snapshot.profile());details.addProperty("localSimulationProfile",local.simulationProfile());
            details.addProperty("serverEnabled",settings.synchronizedSettings()?remote.enabled():null);
            details.addProperty("serverBlockLight",settings.synchronizedSettings()?remote.feature(com.sumirelabs.pcla.config.Feature.BLOCK_LIGHT):null);details.addProperty("serverSkyLight",settings.synchronizedSettings()?remote.feature(com.sumirelabs.pcla.config.Feature.SKY_LIGHT):null);
            var blocks=new JsonObject();details.add("blockDecisions",blocks);
            for(String id:java.util.List.of("minecraft:stone","minecraft:glowstone","minecraft:quartz_block","thaumcraft:crystal_aqua","minecraft:water","minecraft:flowing_water","minecraft:lava","minecraft:flowing_lava"))blocks.addProperty(id,settings.policy().allowed(com.sumirelabs.pcla.config.RuleKind.BLOCK_RENDERING,id,java.util.Map.of()));
            var entities=new JsonObject();details.add("entityDecisions",entities);
            for(String id:java.util.List.of("minecraft:zombie","minecraft:sheep","minecraft:painting"))entities.addProperty(id,settings.policy().allowed(com.sumirelabs.pcla.config.RuleKind.ENTITY_RENDERING,id,java.util.Map.of()));
            var emission=remote.resolve(com.sumirelabs.pcla.config.RuleKind.EMISSION,"minecraft:glowstone",java.util.Map.of(),com.sumirelabs.pcla.light.ColorDefaults.emission("minecraft:glowstone"));
            details.addProperty("serverGlowstoneSourceRgb",!settings.synchronizedSettings() || emission.rgb()==null?null:emission.rgb().packed());
        }
        var regions=new JsonArray(); report.add("regions",regions);
        for(var frame:snapshot.regions().values()) {
            var region=new JsonObject(); region.addProperty("slot",frame.slot); region.addProperty("bounds",frame.bounds.toString());
            region.addProperty("cells",frame.cells()); region.addProperty("digest",FrameDigest.sha256(frame));
            region.addProperty("skyDigest",FrameDigest.skySha256(frame)); region.addProperty("hasBlock",frame.hasBlock()); region.addProperty("hasSky",frame.hasSky());
            int lit=0,colored=0,skyLit=0,skyColored=0;
            for(int cell=0;cell<frame.cells();cell++) {
                if(frame.hasBlock()) { int rgb=frame.color(cell); if(rgb!=0) lit++; if((rgb&15)!=(rgb>>4&15)||(rgb&15)!=(rgb>>8)) colored++; }
                if(frame.hasSky()) { int rgb=frame.skyColor(cell); if(rgb!=0) skyLit++; if((rgb&15)!=(rgb>>4&15)||(rgb&15)!=(rgb>>8)) skyColored++; }
            }
            region.addProperty("lit",lit); region.addProperty("colored",colored); regions.add(region);
            region.addProperty("skyLit",skyLit); region.addProperty("skyColored",skyColored);
        }
        Path output=Path.of("logs","pcla-client-"+System.currentTimeMillis()+".json");
        try { Files.createDirectories(output.getParent()); Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
        catch(java.io.IOException e) { throw new CommandException(e.toString()); }
        sender.sendMessage(new TextComponentString("PCLA client received "+regions.size()+" regions; "+output));
    }
}

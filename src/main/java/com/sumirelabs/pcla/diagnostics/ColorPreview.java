package com.sumirelabs.pcla.diagnostics;

import com.google.gson.*;
import com.sumirelabs.pcla.light.world.ExperimentalRgbBackend;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import java.nio.file.*;
import java.util.*;

/** Disposable, air-only dark-room preview with recorded ownership and automatic shutdown cleanup. */
public final class ColorPreview {
    private static ColorPreview active;
    private final EntityPlayerMP player;
    private final World world;
    private final double x,y,z;
    private final float yaw,pitch;
    private final boolean shapes;
    private final boolean entities;
    private final boolean sky;
    private boolean fluids;
    private boolean palette;
    private ExperimentalRgbBackend paletteBackend;
    private final Map<BlockPos,IBlockState> paletteSources=new LinkedHashMap<>();
    private ExperimentalRgbBackend fluidBackend;
    private final boolean fixedTime;
    private final long originalTime,originalTotalTime;
    private final String originalDaylightCycle;
    private boolean timeChanged;
    private String clockStatus="untouched";
    private final List<net.minecraft.entity.Entity> spawned=new ArrayList<>();
    private int preparationTicks,settledTicks;
    private boolean waitingForLighting;
    private int chunkX,chunkZ;
    private final Map<BlockPos,IBlockState> owned=new LinkedHashMap<>();
    private final Path recovery=Path.of("logs","pcla-preview-"+System.currentTimeMillis()+".json");
    private ColorPreview(EntityPlayerMP player,boolean shapes,boolean entities,boolean sky) {
        this.player=player; world=player.world; x=player.posX; y=player.posY; z=player.posZ;
        yaw=player.rotationYaw; pitch=player.rotationPitch; this.shapes=shapes; this.entities=entities; this.sky=sky;
        fixedTime=sky && Boolean.getBoolean("pcla.previewFixedTime");
        clockStatus=fixedTime ? "planned_fixed_time" : "untouched";
        originalTime=world.getWorldTime(); originalTotalTime=world.getTotalWorldTime(); originalDaylightCycle=world.getGameRules().getString("doDaylightCycle");
    }
    public static boolean running() { return active!=null; }
    public static boolean ready() { return active!=null && !active.waitingForLighting; }
    /** Main-thread observations for the registry/config diagnostic, without changing the scene. */
    public static JsonArray paletteSamples() {
        var samples=new JsonArray();var preview=active;if(preview==null || !preview.palette || !ready())return samples;
        for(var entry:preview.paletteSources.entrySet()) {
            var pos=entry.getKey();var state=preview.world.getBlockState(pos);var item=new JsonObject();
            item.addProperty("id",state.getBlock().getRegistryName().toString());item.addProperty("ownedState",state==entry.getValue());
            item.addProperty("x",pos.getX());item.addProperty("y",pos.getY());item.addProperty("z",pos.getZ());
            int level=state.getLightValue(preview.world,pos);item.addProperty("nativeEmission",level);
            item.addProperty("configuredEmission",com.sumirelabs.pcla.Pcla.colorTable().get(state).emission(level));
            var backend=preview.paletteBackend;
            var result=backend.automaticEnabled()?backend.columnSnapshot(pos.getX()>>4,pos.getZ()>>4):backend.snapshot(0);
            item.addProperty("rgbComplete",result!=null && result.light()!=null);
            if(result!=null && result.light()!=null) {
                var b=result.bounds();int index=pos.getX()-b.x()+b.sizeX()*((pos.getZ()-b.z())+b.sizeZ()*(pos.getY()-b.y()));
                item.addProperty("rgbAtSource",result.light().get(index));
            }
            samples.add(item);
        }
        return samples;
    }

    /** Claim a verified air cell before the upper-column regression changes it. */
    public static void changeSkyProbe(BlockPos pos,IBlockState next) throws java.io.IOException {
        var preview=active;
        if(preview==null || !preview.sky || preview.waitingForLighting) throw new IllegalStateException("Sky preview not ready");
        var height=com.sumirelabs.pulsar.util.WorldUtil.getHeightContext(preview.world);
        if(pos.getY()<height.getMinBlockY() || pos.getY()>height.getMaxBlockY() || !preview.world.isBlockLoaded(pos)
                || !preview.world.getWorldBorder().contains(pos)) throw new IllegalStateException("Sky probe outside loaded world bounds");
        var expected=preview.owned.get(pos);
        if(expected==null) {
            if(preview.world.getBlockState(pos).getBlock()!=Blocks.AIR || preview.world.getTileEntity(pos)!=null)
                throw new IllegalStateException("Sky probe needs unoccupied air");
            expected=Blocks.AIR.getDefaultState();
        }
        if(preview.world.getBlockState(pos)!=expected) throw new IllegalStateException("Sky probe changed externally");
        preview.owned.put(pos.toImmutable(),next); preview.save("active");
        if(!preview.world.setBlockState(pos,next,2)) throw new IllegalStateException("Sky probe edit rejected");
    }
    public static void start(EntityPlayerMP player,ExperimentalRgbBackend backend) throws java.io.IOException {
        start(player,backend,false);
    }
    public static void start(EntityPlayerMP player,ExperimentalRgbBackend backend,boolean shapes) throws java.io.IOException {
        start(player,backend,shapes,false);
    }
    public static void start(EntityPlayerMP player,ExperimentalRgbBackend backend,boolean shapes,boolean entities) throws java.io.IOException {
        start(player,backend,shapes,entities,false);
    }
    public static void start(EntityPlayerMP player,ExperimentalRgbBackend backend,boolean shapes,boolean entities,boolean sky) throws java.io.IOException {
        start(player,backend,shapes,entities,sky,false);
    }
    public static void start(EntityPlayerMP player,ExperimentalRgbBackend backend,boolean shapes,boolean entities,boolean sky,boolean fluids) throws java.io.IOException {
        start(player,backend,shapes,entities,sky,fluids,false);
    }
    public static void startPalette(EntityPlayerMP player,ExperimentalRgbBackend backend)throws java.io.IOException {
        start(player,backend,false,false,false,false,true);
    }
    private static void start(EntityPlayerMP player,ExperimentalRgbBackend backend,boolean shapes,boolean entities,boolean sky,boolean fluids,boolean palette) throws java.io.IOException {
        if(active!=null) throw new IllegalStateException("Preview already active; use clearpreview");
        if((fluids || palette) && !backend.slots().isEmpty())throw new IllegalStateException("Comparison requires no existing manually selected regions");
        if(sky && !backend.skyEnabled()) throw new IllegalStateException("Start with -Dpcla.experimentalSkyRgb=true and SKY_LIGHT enabled");
        if(!player.isSpectator() && !player.capabilities.isCreativeMode) throw new IllegalStateException("Preview requires creative or spectator mode");
        int bx=player.getPosition().getX()&~15, bz=player.getPosition().getZ()&~15;
        int by=Math.min(224,Math.max(16,((player.getPosition().getY()+31)>>4)<<4));
        while(by<=224 && !emptyVolume(player.world,bx,by,bz)) by+=16;
        if(by>224) throw new IllegalStateException("No fully loaded empty 13x7x9 preview volume above the player");
        var preview=new ColorPreview(player,shapes,entities,sky);
        preview.fluids=fluids;preview.palette=palette;if(palette)preview.paletteBackend=backend;
        if((fluids || palette) && !backend.automaticEnabled())preview.fluidBackend=backend;
        for(int dx=0;dx<13;dx++) for(int dy=0;dy<7;dy++) for(int dz=0;dz<9;dz++) {
            var pos=new BlockPos(bx+dx,by+dy,bz+dz);
            if(!player.world.isBlockLoaded(pos) || !player.world.getWorldBorder().contains(pos)
                    || player.world.getBlockState(pos).getBlock()!=Blocks.AIR || player.world.getTileEntity(pos)!=null)
                throw new IllegalStateException("Preview needs a fully loaded empty 13x7x9 space above the player");
            if(dx==0 || dx==12 || dy==0 || dy==6 || dz==0 || dz==8) preview.owned.put(pos,Blocks.QUARTZ_BLOCK.getDefaultState());
        }
        var glass=Blocks.STAINED_GLASS.getDefaultState().withProperty(BlockStainedGlass.COLOR,EnumDyeColor.BLUE);
        if(!palette) {
            preview.owned.put(new BlockPos(bx+3,by+1,bz+5),Blocks.REDSTONE_TORCH.getDefaultState());
            var source=new BlockPos(bx+9,by+2,bz+5);
            for(var face:net.minecraft.util.EnumFacing.values()) preview.owned.put(source.offset(face),glass);
            preview.owned.put(source,Blocks.GLOWSTONE.getDefaultState());
        } else {
            String[] colors={"red","blue","green"};
            for(int row=0;row<2;row++)for(int column=0;column<3;column++) {
                var id=new net.minecraft.util.ResourceLocation("thaumcraft",(row==0?"candle_":"nitor_")+colors[column]);
                if(!net.minecraft.block.Block.REGISTRY.containsKey(id))throw new IllegalStateException("Palette preview requires "+id);
                var pos=new BlockPos(bx+3+column*3,by+1+row,bz+3+row*3);
                preview.paletteSources.put(pos,net.minecraft.block.Block.REGISTRY.getObject(id).getDefaultState());
            }
            preview.owned.putAll(preview.paletteSources);
        }
        if(sky) {
            preview.owned.put(new BlockPos(bx+3,by+6,bz+4),glass.withProperty(BlockStainedGlass.COLOR,EnumDyeColor.RED));
            if(!preview.fixedTime) preview.owned.put(new BlockPos(bx+6,by+6,bz+4),Blocks.GLASS.getDefaultState());
            preview.owned.put(new BlockPos(bx+9,by+6,bz+4),glass);
        }
        if(shapes) {
            var slab=Blocks.STONE_SLAB.getDefaultState();
            preview.owned.put(new BlockPos(bx+3,by+1,bz+4),slab);
            preview.owned.put(new BlockPos(bx+5,by+1,bz+4),slab.withProperty(net.minecraft.block.BlockSlab.HALF,net.minecraft.block.BlockSlab.EnumBlockHalf.TOP));
            var stairs=Blocks.QUARTZ_STAIRS.getDefaultState();
            preview.owned.put(new BlockPos(bx+7,by+1,bz+4),stairs.withProperty(net.minecraft.block.BlockStairs.FACING,net.minecraft.util.EnumFacing.NORTH));
            preview.owned.put(new BlockPos(bx+9,by+1,bz+4),stairs.withProperty(net.minecraft.block.BlockStairs.FACING,net.minecraft.util.EnumFacing.SOUTH)
                    .withProperty(net.minecraft.block.BlockStairs.HALF,net.minecraft.block.BlockStairs.EnumHalf.TOP));
            preview.owned.put(new BlockPos(bx+6,by+1,bz+3),Blocks.CARPET.getDefaultState());
        }
        if(entities) {
            for(int side=0;side<2;side++) {
                var sheep=new net.minecraft.entity.passive.EntitySheep(player.world);
                sheep.setPosition(bx+(fluids?(side==0?1.5:11.5):(side==0?3.5:9.5)),by+1,bz+(fluids?5.5:3.5));
                sheep.setFleeceColor(EnumDyeColor.WHITE); sheep.setNoAI(true); sheep.setNoGravity(true);
                sheep.setSilent(true); sheep.setEntityInvulnerable(true); sheep.enablePersistence();
                sheep.setCustomNameTag(side==0 ? "Red probe" : "Blue probe"); sheep.setAlwaysRenderNameTag(true);
                preview.spawned.add(sheep);
            }
            var anchor=new BlockPos(bx+6,by+3,bz+7);
            // The String-art constructor is client-only in 1.12.2 and absent on dedicated servers.
            var painting=new net.minecraft.entity.item.EntityPainting(player.world,anchor,net.minecraft.util.EnumFacing.NORTH);
            painting.art=net.minecraft.entity.item.EntityPainting.EnumArt.FIGHTERS;
            painting.setPosition(anchor.getX(),anchor.getY(),anchor.getZ());
            preview.spawned.add(painting);
        }
        if(fluids) {
            // Contained static-liquid states make comparison geometry repeatable without flowing outside the fixture.
            var pools=new LinkedHashMap<BlockPos,IBlockState>();
            pools.put(new BlockPos(bx+3,by+1,bz+3),Blocks.WATER.getDefaultState());
            pools.put(new BlockPos(bx+8,by+1,bz+3),Blocks.WATER.getDefaultState());
            pools.put(new BlockPos(bx+9,by+1,bz+3),Blocks.WATER.getDefaultState().withProperty(net.minecraft.block.BlockLiquid.LEVEL,4));
            pools.put(new BlockPos(bx+6,by+1,bz+5),Blocks.LAVA.getDefaultState());
            for(var pool:pools.keySet())for(var face:net.minecraft.util.EnumFacing.Plane.HORIZONTAL) {
                var wall=pool.offset(face);if(!pools.containsKey(wall))preview.owned.put(wall,Blocks.GLASS.getDefaultState());
            }
            preview.owned.put(new BlockPos(bx+2,by+1,bz+6),Blocks.STONE_SLAB.getDefaultState());
            preview.owned.put(new BlockPos(bx+4,by+1,bz+6),Blocks.QUARTZ_STAIRS.getDefaultState());
            preview.owned.putAll(pools); // Place fluid after containment; alpha/height/UV remain the native renderer's work.
        }
        preview.save("pending"); // All intended changes are recorded while every affected cell is still air.
        active=preview;
        try {
            if(preview.fixedTime) {
                preview.timeChanged=true; preview.clockStatus="held_at_noon";
                player.world.getGameRules().setOrCreateGameRule("doDaylightCycle","false"); player.world.setWorldTime(6000);
            }
            // Automatic columns already cover this scene's entire height. Avoid adding a
            // redundant tenth working owner to a nine-column automatic test window.
            if(!(fluids || palette) || !backend.automaticEnabled())backend.watch(bx,by,bz,2);
            for(var entry:preview.owned.entrySet()) player.world.setBlockState(entry.getKey(),entry.getValue(),2);
            // Wait for ordinary scalar work before spawning comparison entities.
            // Do not force a relight: this fixture must exercise normal bulk packet delivery.
            preview.chunkX=bx>>4; preview.chunkZ=bz>>4;
            preview.waitingForLighting=true;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(preview);
            player.connection.setPlayerLocation(bx+6.5,by+1,bz+1.5,0,8);
            preview.save("preparing_scalar_baseline");
        } catch(RuntimeException|java.io.IOException e) { stop(); throw e; }
        player.sendMessage(new TextComponentString("PCLA preview waiting for ordinary scalar updates; /pcl clearpreview restores the scene."));
    }
    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
    public void tick(net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent event) {
        if(event.phase!=net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END || active!=this || !waitingForLighting) return;
        var manager=((com.sumirelabs.pulsar.world.PulsarWorld)world).pulsar$getLightManager();
        if(++preparationTicks>600) { player.sendMessage(new TextComponentString("PCLA preview scalar preparation timed out; restoring.")); stop(); return; }
        if(manager.hasPendingLightWork(chunkX,chunkZ)) return;
        if(++settledTicks<20) return; // Allow deferred section delivery and client import before entity spawning.
        try {
            for(var entity:spawned) {
                if(entity instanceof net.minecraft.entity.item.EntityPainting painting && !painting.onValidSurface()) throw new IllegalStateException("Painting preview has no valid support");
                if(!world.spawnEntity(entity)) throw new IllegalStateException("Preview entity spawn rejected");
            }
            waitingForLighting=false;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(this);
            save("active");
            player.sendMessage(new TextComponentString("PCLA "+(sky?"sky":entities?"entity":shapes?"shape":"dark-room")+" preview ready after ordinary scalar updates. "
                    +(entities?"/pcc entityoff|entityon compares entities; ":"")+"/pcl clearpreview restores the scene."));
        } catch(RuntimeException|java.io.IOException e) { player.sendMessage(new TextComponentString("PCLA preview failed: "+e)); stop(); }
    }
    private void save(String status) throws java.io.IOException {
        var report=new JsonObject(); report.addProperty("status",status);
        report.addProperty("shapePreview",shapes);
        report.addProperty("entityPreview",entities);
        report.addProperty("skyPreview",sky);
        report.addProperty("fluidPreview",fluids);
        report.addProperty("palettePreview",palette);
        report.addProperty("selectedManualRegion",!(fluids || palette) || fluidBackend!=null);
        report.addProperty("worldFolder",world.getSaveHandler().getWorldDirectory().getName());
        report.addProperty("dimension",world.provider.getDimension());
        report.addProperty("fixedTime",fixedTime); report.addProperty("originalTime",originalTime);
        report.addProperty("originalTotalTime",originalTotalTime);
        report.addProperty("originalDaylightCycle",originalDaylightCycle); report.addProperty("clockStatus",clockStatus);
        report.addProperty("explicitScalarRelight",false);
        var actors=new JsonArray(); report.add("ownedEntities",actors);
        for(var entity:spawned) {
            var actor=new JsonObject(); actor.addProperty("uuid",entity.getUniqueID().toString());
            actor.addProperty("type",String.valueOf(net.minecraft.entity.EntityList.getKey(entity)));
            var at=new BlockPos(entity.posX,entity.posY+entity.getEyeHeight(),entity.posZ);
            if(world.isBlockLoaded(at)) {
                actor.addProperty("blockLight",world.getLightFor(net.minecraft.world.EnumSkyBlock.BLOCK,at));
                actor.addProperty("skyLight",world.getLightFor(net.minecraft.world.EnumSkyBlock.SKY,at));
            }
            actor.addProperty("dead",entity.isDead); actors.add(actor);
        }
        report.addProperty("originalX",x); report.addProperty("originalY",y); report.addProperty("originalZ",z);
        var cells=new JsonArray(); report.add("originalAirCells",cells);
        for(var entry:owned.entrySet()) {
            var cell=new JsonObject(); var p=entry.getKey();
            cell.addProperty("x",p.getX()); cell.addProperty("y",p.getY()); cell.addProperty("z",p.getZ());
            cell.addProperty("placed",entry.getValue().toString()); cells.add(cell);
        }
        Files.createDirectories(recovery.getParent()); Files.writeString(recovery,new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    private static boolean emptyVolume(World world,int x,int y,int z) {
        var pos=new BlockPos.MutableBlockPos();
        for(int dx=0;dx<13;dx++) for(int dy=0;dy<7;dy++) for(int dz=0;dz<9;dz++) {
            pos.setPos(x+dx,y+dy,z+dz);
            if(!world.isBlockLoaded(pos) || !world.getWorldBorder().contains(pos)
                    || world.getBlockState(pos).getBlock()!=Blocks.AIR || world.getTileEntity(pos)!=null) return false;
        }
        return true;
    }
    public static void stop() {
        var preview=active; if(preview==null) return; active=null;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(preview);
        for(var entity:preview.spawned) { entity.setDead(); preview.world.removeEntity(entity); }
        int retained=0;
        var cleanupOrder=new ArrayList<>(preview.owned.entrySet());
        cleanupOrder.sort(Comparator.comparingInt(entry->entry.getValue().getBlock() instanceof net.minecraft.block.BlockLiquid?0:1));
        for(var entry:cleanupOrder) {
            if(!preview.world.isBlockLoaded(entry.getKey())) { retained++; continue; }
            var actual=preview.world.getBlockState(entry.getKey());
            if(actual==entry.getValue() || OwnedLiquidState.matches(entry.getValue().toString(),actual.toString()))
                preview.world.setBlockState(entry.getKey(),Blocks.AIR.getDefaultState(),2);
            else if(preview.world.getBlockState(entry.getKey()).getBlock()!=Blocks.AIR) retained++;
        }
        if(preview.timeChanged) {
            if(preview.world.getWorldTime()==6000 && preview.world.getGameRules().getString("doDaylightCycle").equals("false")) {
                long elapsed=preview.originalDaylightCycle.equals("true") ? preview.world.getTotalWorldTime()-preview.originalTotalTime : 0;
                preview.world.setWorldTime(preview.originalTime+elapsed);
                preview.world.getGameRules().setOrCreateGameRule("doDaylightCycle",preview.originalDaylightCycle);
                preview.clockStatus="restored";
            } else preview.clockStatus="retained_external_change";
        }
        if(preview.player.connection!=null) preview.player.connection.setPlayerLocation(preview.x,preview.y,preview.z,preview.yaw,preview.pitch);
        if(preview.fluidBackend!=null)preview.fluidBackend.remove(0);
        try { preview.save(retained==0 ? "restored" : "unrestored_or_externally_changed_cells="+retained); }
        catch(java.io.IOException e) { org.apache.logging.log4j.LogManager.getLogger("PCLA").error("Could not write preview recovery result",e); }
    }
}

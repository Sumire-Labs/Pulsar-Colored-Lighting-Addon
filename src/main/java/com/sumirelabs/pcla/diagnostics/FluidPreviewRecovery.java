package com.sumirelabs.pcla.diagnostics;

import com.google.gson.*;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import java.nio.file.*;

/** Explicit, one-shot recovery of recorded fixture liquids before the first normal world tick. */
public final class FluidPreviewRecovery {
    public static void restore(MinecraftServer server) {
        String name=System.getProperty("pcla.recoverFluidPreview");if(name==null)return;
        if(!name.matches("pcla-preview-\\d+\\.json"))throw new IllegalArgumentException("Invalid fixture recovery filename");
        Path file=Path.of("logs",name);
        try {
            if(Files.size(file)>2_000_000)throw new IllegalArgumentException("Fixture recovery record too large");
            var record=new JsonParser().parse(Files.readString(file)).getAsJsonObject();
            if(record.has("fluidRecoveryCompleted") && record.get("fluidRecoveryCompleted").getAsBoolean())return;
            var world=server.getWorld(record.get("dimension").getAsInt());
            if(world==null || !world.getSaveHandler().getWorldDirectory().getName().equals(record.get("worldFolder").getAsString()))return;
            var cells=record.getAsJsonArray("originalAirCells");if(cells.size()>8192)throw new IllegalArgumentException("Too many fixture cells");
            var candidates=new java.util.ArrayList<BlockPos>();var removed=new JsonArray();BlockPos fixtureChunk=null;
            for(var value:cells) {
                var cell=value.getAsJsonObject();String expected=cell.get("placed").getAsString();
                if(!OwnedLiquidState.matches(expected,expected))continue;
                var pos=new BlockPos(cell.get("x").getAsInt(),cell.get("y").getAsInt(),cell.get("z").getAsInt());
                if(fixtureChunk==null)fixtureChunk=pos;
                else if((fixtureChunk.getX()>>4)!=(pos.getX()>>4) || (fixtureChunk.getZ()>>4)!=(pos.getZ()>>4))
                    throw new IllegalStateException("Fluid recovery is restricted to one recorded fixture chunk");
                // loadChunk reads an existing saved chunk; unlike provideChunk, it does not generate one.
                if(!world.isBlockLoaded(pos) && world.getChunkProvider().loadChunk(pos.getX()>>4,pos.getZ()>>4)==null)
                    throw new IllegalStateException("Recorded fixture chunk is not present on disk: "+pos);
                var actual=world.getBlockState(pos);
                if(actual.getBlock()==Blocks.AIR)continue;
                if(!OwnedLiquidState.matches(expected,actual.toString()))throw new IllegalStateException("Recovery retained an externally changed cell: "+pos+" "+actual);
                candidates.add(pos);var item=new JsonObject();item.addProperty("position",pos.toString());item.addProperty("before",actual.toString());removed.add(item);
            }
            for(var pos:candidates){world.setBlockState(pos,Blocks.AIR.getDefaultState(),2);if(world.getBlockState(pos).getBlock()!=Blocks.AIR)throw new IllegalStateException("Fixture liquid recovery failed: "+pos);}
            record.add("recoveredFluidVariants",removed);record.addProperty("fluidRecoveryCompleted",true);
            Files.writeString(file,new GsonBuilder().setPrettyPrinting().create().toJson(record));
            org.apache.logging.log4j.LogManager.getLogger("PCLA").info("Recovered {} recorded fixture liquid variants before world ticking: {}",candidates.size(),name);
        } catch(java.io.IOException|RuntimeException error){throw new IllegalStateException("Cannot safely recover fluid fixture "+name,error);}
    }
    private FluidPreviewRecovery() {}
}

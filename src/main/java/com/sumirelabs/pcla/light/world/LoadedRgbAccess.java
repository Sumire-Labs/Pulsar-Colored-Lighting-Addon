package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import java.util.Map;
import javax.annotation.Nullable;

/** Contextual block callbacks may inspect loaded neighbors, but cannot load/generate chunks. */
final class LoadedRgbAccess implements IBlockAccess {
    private final World world;
    private final WorldHeightContext height;
    private final Map<Long,Chunk> chunks;
    LoadedRgbAccess(World world,WorldHeightContext height,Map<Long,Chunk> chunks) {
        this.world=world;this.height=height;this.chunks=Map.copyOf(chunks);
    }
    private Chunk chunk(BlockPos pos){
        Chunk chunk=chunks.get(RegionPublication.key(pos.getX()>>4,pos.getZ()>>4));
        // The host's immutable loaded-chunk map never invokes a provider/load.
        // Contextual getters at a guard edge may still inspect an already loaded neighbor.
        return chunk!=null?chunk:world instanceof com.sumirelabs.pulsar.api.ExtendedWorld loaded
                ?loaded.pulsar$getAnyChunkImmediately(pos.getX()>>4,pos.getZ()>>4):null;
    }
    public IBlockState getBlockState(BlockPos pos) {
        if(pos.getY()<height.getMinBlockY()||pos.getY()>height.getMaxBlockY())return Blocks.AIR.getDefaultState();
        Chunk chunk=chunk(pos);if(chunk==null)return Blocks.AIR.getDefaultState();
        int index=height.getStorageIndex(pos.getY()>>4);var sections=chunk.getBlockStorageArray();
        var section=index>=0&&index<sections.length?sections[index]:null;
        return section==null?Blocks.AIR.getDefaultState():section.get(pos.getX()&15,pos.getY()&15,pos.getZ()&15);
    }
    @Nullable public TileEntity getTileEntity(BlockPos pos) {
        Chunk chunk=chunk(pos);return chunk==null?null:chunk.getTileEntity(pos,Chunk.EnumCreateEntityType.CHECK);
    }
    public int getCombinedLight(BlockPos pos,int minimumBlockLight) {
        int sky=scalar(pos,EnumSkyBlock.SKY),block=scalar(pos,EnumSkyBlock.BLOCK);
        if(getBlockState(pos).useNeighborBrightness())for(EnumFacing direction:EnumFacing.values())if(direction!=EnumFacing.DOWN) {
            var neighbor=pos.offset(direction);sky=Math.max(sky,scalar(neighbor,EnumSkyBlock.SKY));block=Math.max(block,scalar(neighbor,EnumSkyBlock.BLOCK));
        }
        block=Math.max(minimumBlockLight,block);
        return sky<<20|Math.max(0,Math.min(15,block))<<4;
    }
    private int scalar(BlockPos pos,EnumSkyBlock lane) {
        if(lane==EnumSkyBlock.SKY&&!world.provider.hasSkyLight())return 0;
        if(pos.getY()>height.getMaxBlockY())return lane==EnumSkyBlock.SKY?15:0;
        if(pos.getY()<height.getMinBlockY())return 0;
        Chunk chunk=chunk(pos);return chunk==null?lane.defaultLightValue:chunk.getLightFor(lane,pos);
    }
    public boolean isAirBlock(BlockPos pos){return getBlockState(pos).getBlock().isAir(getBlockState(pos),this,pos);}
    public Biome getBiome(BlockPos pos){Chunk chunk=chunk(pos);return chunk==null?Biomes.PLAINS:chunk.getBiome(pos,world.getBiomeProvider());}
    public int getStrongPower(BlockPos pos,EnumFacing direction){return getBlockState(pos).getStrongPower(this,pos,direction);}
    public WorldType getWorldType(){return world.getWorldType();}
    public boolean isSideSolid(BlockPos pos,EnumFacing side,boolean fallback){return chunk(pos)==null?fallback:getBlockState(pos).isSideSolid(this,pos,side);}
}

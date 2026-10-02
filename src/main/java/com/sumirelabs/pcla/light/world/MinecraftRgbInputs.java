package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.Pcla;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import java.util.Map;

/** Read stable captured chunk identities; the caller owns revision validation. */
final class MinecraftRgbInputs {
    private static final int[] PULSAR_DIRECTION={0,1,4,5,2,3};
    private MinecraftRgbInputs() {}
    static void read(World world,WorldHeightContext height,Map<Long,Chunk> chunks,BlockPos.MutableBlockPos pos,
                     int x,int y,int z,CapturedRgbRegion.Input value) {
        read(world,height,chunks.get(RegionPublication.key(x>>4,z>>4)),pos,x,y,z,value);
    }
    static void read(World world,WorldHeightContext height,Chunk chunk,BlockPos.MutableBlockPos pos,
                     int x,int y,int z,CapturedRgbRegion.Input value) {
        read(world,height,chunk,pos,x,y,z,value,world);
    }
    static void read(World world,WorldHeightContext height,Chunk chunk,BlockPos.MutableBlockPos pos,
                     int x,int y,int z,CapturedRgbRegion.Input value,net.minecraft.world.IBlockAccess access) {
        IBlockState state=Blocks.AIR.getDefaultState();
        if(y>=height.getMinBlockY() && y<=height.getMaxBlockY()) {
            if(chunk==null) throw new IllegalStateException("Missing captured chunk");
            int index=height.getStorageIndex(y>>4); var storage=chunk.getBlockStorageArray();
            var section=index>=0 && index<storage.length ? storage[index] : null;
            if(section!=null) state=section.get(x&15,y&15,z&15);
        }
        int info=LightInfo.resolveContextual(LightInfo.of(state),state,access,pos,x,y,z);
        var color=Pcla.colorTable().get(state);
        value.source=color.emission(LightInfo.emission(info)); value.blockedFaces=0;
        for(int d=0;d<6;d++) {
            int face=PULSAR_DIRECTION[d]; boolean solid=LightInfo.isFaceSolid(info,face);
            if(solid) value.blockedFaces|=1<<d;
            int absorption=LightInfo.absorption(info,state,face);
            // Preserve truly transparent media before either lane's minimum step.
            // Dynamic interface opacity lacks a raw directional-zero API here;
            // keep its conservative scalar absorption until a dedicated adapter exists.
            if(LightInfo.opacity(info)==0 && (info&LightInfo.DYNAMIC)==0 && !solid && absorption==1) absorption=0;
            value.incoming[d]=color.absorption(absorption);
        }
        value.contextual=LightInfo.hasContextualValues(info);
        if(com.sumirelabs.pulsar.compat.FluidLightBridge.LOADED && chunk!=null
                && y>=height.getMinBlockY() && y<=height.getMaxBlockY())
            com.sumirelabs.pcla.compat.FluidRgbInputs.merge(access,chunk,pos,x,y,z,state,info,value);
    }
}

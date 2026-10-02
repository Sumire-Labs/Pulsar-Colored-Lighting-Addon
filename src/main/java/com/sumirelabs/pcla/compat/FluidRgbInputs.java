package com.sumirelabs.pcla.compat;

import com.sumirelabs.pcla.Pcla;
import com.sumirelabs.pcla.light.PackedRgb;
import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import com.sumirelabs.pulsar.light.engine.LightInfo;
import git.jbredwards.fluidlogged_api.api.capability.IFluidStateCapability;
import git.jbredwards.fluidlogged_api.api.util.FluidState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/** Optional Fluidlogged API input bridge. Mirrors Pulsar's uniform max absorber semantics per color. */
public final class FluidRgbInputs {
    public static void merge(net.minecraft.world.IBlockAccess world,Chunk chunk,BlockPos.MutableBlockPos pos,int x,int y,int z,
                             IBlockState host,int hostInfo,CapturedRgbRegion.Input result) {
        var capability=IFluidStateCapability.get(chunk);if(capability==null)return;
        var fluid=capability.getContainer(y).getFluidState(x,y,z,FluidState.EMPTY);if(fluid.isEmpty())return;
        var state=fluid.getState();
        int info=LightInfo.resolveContextual(LightInfo.of(state),state,world,pos,x,y,z);
        var colors=Pcla.colorTable();var hostColor=colors.get(host);var fluidColor=colors.get(state);
        merge(result,fluidColor.emission(LightInfo.emission(info)),
            hostColor.absorption(LightInfo.opacity(hostInfo)),fluidColor.absorption(LightInfo.opacity(info)),
            LightInfo.hasContextualValues(info));
    }
    public static void merge(CapturedRgbRegion.Input result,int fluidEmission,int hostAbsorption,int fluidAbsorption,boolean contextual) {
        result.source=PackedRgb.union(result.source,fluidEmission);
        int absorption=PackedRgb.union(hostAbsorption,fluidAbsorption);
        result.blockedFaces=0;java.util.Arrays.fill(result.incoming,absorption);
        result.contextual|=contextual;
    }
    private FluidRgbInputs(){}
}

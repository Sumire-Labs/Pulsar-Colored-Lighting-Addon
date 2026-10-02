package com.sumirelabs.pcla.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sumirelabs.pcla.client.FluidTint;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Intercept only native liquid rendering, preserving geometry, texture, alpha and scalar lightmap. */
@Mixin(BlockFluidRenderer.class)
public abstract class MixinFluidTint {
    @WrapMethod(method="renderFluid")
    private boolean pcla$scope(IBlockAccess access,IBlockState state,BlockPos pos,BufferBuilder buffer,Operation<Boolean> original) {
        FluidTint.begin(access,state,pos);
        try{return original.call(access,state,pos,buffer);}finally{FluidTint.end();}
    }
    @WrapOperation(method="renderFluid",at=@At(value="INVOKE",target="Lnet/minecraft/block/state/IBlockState;getPackedLightmapCoords(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;)I"))
    private int pcla$light(IBlockState state,IBlockAccess access,BlockPos pos,Operation<Integer> original) {
        int packed=original.call(state,access,pos);FluidTint.sample(pos,packed);return FluidTint.coordinates(packed);
    }
    @Redirect(method="renderFluid",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/BufferBuilder;color(FFFF)Lnet/minecraft/client/renderer/BufferBuilder;"))
    private BufferBuilder pcla$color(BufferBuilder buffer,float red,float green,float blue,float alpha) {
        return FluidTint.color(buffer,red,green,blue,alpha);
    }
}

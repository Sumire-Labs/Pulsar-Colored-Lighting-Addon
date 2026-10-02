package com.sumirelabs.pcla.mixin;

import com.sumirelabs.pcla.client.BlockTint;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(BlockModelRenderer.class)
public abstract class MixinBlockTint {
    private static final String MODEL="renderModel(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/block/model/IBakedModel;Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/client/renderer/BufferBuilder;ZJ)Z";
    @WrapMethod(method=MODEL)
    private boolean pcla$render(IBlockAccess access,IBakedModel model,IBlockState state,BlockPos pos,BufferBuilder buffer,boolean sides,long seed,Operation<Boolean> original) {
        BlockTint.begin(access,state,pos);
        try { return original.call(access,model,state,pos,buffer,sides,seed); }
        finally { BlockTint.end(); }
    }
    @Redirect(method={"renderQuadsSmooth","renderQuadsFlat"},at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/block/model/BakedQuad;getFace()Lnet/minecraft/util/EnumFacing;"))
    private EnumFacing pcla$face(BakedQuad quad) {
        var face=quad.getFace(); if(BlockTint.INSTALLED) BlockTint.quad(quad); return face;
    }
    @Redirect(method={"renderQuadsSmooth","renderQuadsFlat"},at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/BufferBuilder;putPosition(DDD)V"))
    private void pcla$tint(BufferBuilder buffer,double x,double y,double z) {
        buffer.putPosition(x,y,z); if(BlockTint.INSTALLED) BlockTint.apply(buffer);
    }
}

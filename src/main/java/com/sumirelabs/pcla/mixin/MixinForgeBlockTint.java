package com.sumirelabs.pcla.mixin;

import com.sumirelabs.pcla.client.BlockTint;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.model.pipeline.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Forge's lighter bypasses vanilla renderQuads, so tint its completed quad exactly once. */
@Mixin(value=ForgeBlockModelRenderer.class,remap=false)
public abstract class MixinForgeBlockTint {
    @WrapMethod(method="render",remap=false)
    private static boolean pcla$render(VertexLighterFlat lighter,IBlockAccess access,IBakedModel model,IBlockState state,BlockPos pos,BufferBuilder buffer,boolean sides,long seed,Operation<Boolean> original) {
        BlockTint.begin(access,state,pos);
        try { return original.call(lighter,access,model,state,pos,buffer,sides,seed); }
        finally { BlockTint.end(); }
    }
    @Redirect(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/block/model/BakedQuad;pipe(Lnet/minecraftforge/client/model/pipeline/IVertexConsumer;)V"))
    private static void pcla$tint(BakedQuad quad,IVertexConsumer consumer,VertexLighterFlat lighter,IBlockAccess access,IBakedModel model,IBlockState state,BlockPos pos,BufferBuilder buffer,boolean sides,long seed) {
        int before=buffer.getVertexCount();
        quad.pipe(consumer);
        if(BlockTint.INSTALLED && buffer.getVertexCount()==before+4) { BlockTint.quad(quad); BlockTint.apply(buffer); }
    }
}

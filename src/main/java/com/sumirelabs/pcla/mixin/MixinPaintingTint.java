package com.sumirelabs.pcla.mixin;

import com.sumirelabs.pcla.client.EntityTint;
import net.minecraft.client.renderer.entity.RenderPainting;
import net.minecraft.entity.item.EntityPainting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(RenderPainting.class)
public abstract class MixinPaintingTint {
    @WrapMethod(method="renderPainting(Lnet/minecraft/entity/item/EntityPainting;IIII)V")
    private void pcla$rgbGrid(EntityPainting painting,int width,int height,int u,int v,Operation<Void> original) {
        try(var scope=com.sumirelabs.pcla.client.GpuPaintingLight.begin(painting,width,height)){original.call(painting,width,height,u,v);}
    }
    @Inject(method="setLightmap",at=@At("TAIL"))
    private void pcla$gpuTile(EntityPainting painting,float localX,float localY,CallbackInfo ci) {
        com.sumirelabs.pcla.client.GpuEntityLight.painting(painting,localX,localY);
    }
    @Inject(method="setLightmap",at=@At("HEAD"))
    private void pcla$tile(EntityPainting painting,float localX,float localY,CallbackInfo ci) {
        EntityTint.paintingTile(painting,localX,localY);
    }
}

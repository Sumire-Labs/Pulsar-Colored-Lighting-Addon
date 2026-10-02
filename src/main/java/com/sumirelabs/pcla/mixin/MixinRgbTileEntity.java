package com.sumirelabs.pcla.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pcla.client.GpuEntityLight;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TileEntityRendererDispatcher.class)
public abstract class MixinRgbTileEntity {
    @WrapMethod(method="render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V")
    private void pcla$render(TileEntity tile,double x,double y,double z,float partial,int destroy,float alpha,Operation<Void> original) {
        try(var scope=GpuEntityLight.tile(tile)){original.call(tile,x,y,z,partial,destroy,alpha);}
    }
}

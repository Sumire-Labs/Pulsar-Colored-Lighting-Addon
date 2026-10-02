package com.sumirelabs.pcla.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pcla.client.RgbGpuLightmap;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RenderGlobal.class)
public abstract class MixinRgbTerrain {
    @WrapMethod(method="renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I")
    private int pcla$lightmap(BlockRenderLayer layer,double partial,int pass,Entity entity,Operation<Integer> original) {
        try(var scope=RgbGpuLightmap.terrainLayer()){return original.call(layer,partial,pass,entity);}
    }
}

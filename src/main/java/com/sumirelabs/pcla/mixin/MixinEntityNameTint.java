package com.sumirelabs.pcla.mixin;

import com.sumirelabs.pcla.client.EntityTint;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Render.class)
public abstract class MixinEntityNameTint {
    @WrapMethod(method="renderLivingLabel")
    private void pcla$label(Entity entity,String text,double x,double y,double z,int distance,Operation<Void> original) {
        try(var gpu=com.sumirelabs.pcla.client.GpuEntityLight.neutral();var scope=EntityTint.neutral()) { EntityTint.reapply(); original.call(entity,text,x,y,z,distance); }
    }
}

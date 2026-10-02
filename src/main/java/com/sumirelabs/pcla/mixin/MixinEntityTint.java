package com.sumirelabs.pcla.mixin;

import com.sumirelabs.pcla.client.EntityTint;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(RenderManager.class)
public abstract class MixinEntityTint {
    @Shadow private boolean renderOutlines;
    @Redirect(method="renderEntity",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/Render;doRender(Lnet/minecraft/entity/Entity;DDDFF)V"))
    private void pcla$render(Render<Entity> renderer,Entity entity,double x,double y,double z,float yaw,float partialTicks) {
        try(var gpu=com.sumirelabs.pcla.client.GpuEntityLight.begin(entity,renderOutlines);
            var scope=EntityTint.begin(entity,renderOutlines)) {
            EntityTint.reapply(); renderer.doRender(entity,x,y,z,yaw,partialTicks);
        }
    }
}

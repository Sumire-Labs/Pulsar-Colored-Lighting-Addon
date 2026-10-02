package com.sumirelabs.pcla.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pcla.client.GpuEntityLight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.*;

@Mixin(EntityRenderer.class)
public abstract class MixinRgbHand {
    @Shadow private Minecraft mc;
    @WrapMethod(method="renderHand(FI)V")
    private void pcla$hand(float partial,int pass,Operation<Void> original) {
        if(mc.player==null){original.call(partial,pass);return;}
        com.sumirelabs.pcla.client.RenderStateProbe.begin();
        try {
            try(var scope=GpuEntityLight.begin(mc.player,false)) {
                com.sumirelabs.pcla.client.RenderStateProbe.mark("scopeBound");original.call(partial,pass);
                com.sumirelabs.pcla.client.RenderStateProbe.mark("afterHand");
            }
            com.sumirelabs.pcla.client.RenderStateProbe.mark("scopeRestored");
        } finally {com.sumirelabs.pcla.client.RenderStateProbe.end();}
    }
}

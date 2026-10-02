package com.sumirelabs.pcla.mixin;

import com.sumirelabs.pcla.client.GpuPaintingLight;
import net.minecraft.client.renderer.BufferBuilder;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets="com.sumirelabs.pulsar.light.PaintingLightSampler",remap=false)
public abstract class MixinPulsarPaintingRgb {
    @ModifyVariable(method="writeVertexLight",at=@At("HEAD"),argsOnly=true,ordinal=0,remap=false)
    private static int pcla$vertex(int packed,BufferBuilder buffer,int originalPacked) {
        return GpuPaintingLight.vertex(buffer,packed);
    }
}

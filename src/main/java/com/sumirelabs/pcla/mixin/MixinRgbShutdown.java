package com.sumirelabs.pcla.mixin;

import com.sumirelabs.pcla.client.RgbGpuLightmap;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinRgbShutdown {
    @Inject(method="shutdownMinecraftApplet",at=@At("HEAD"))
    private void pcla$release(CallbackInfo ci){RgbGpuLightmap.shutdown();}
}

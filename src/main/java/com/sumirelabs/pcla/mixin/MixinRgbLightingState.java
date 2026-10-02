package com.sumirelabs.pcla.mixin;

import com.sumirelabs.pcla.client.RgbGpuLightmap;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
public abstract class MixinRgbLightingState {
    @Inject(method="callList",at=@At("HEAD"))
    private static void pcla$list(CallbackInfo ci){com.sumirelabs.pcla.client.RenderStateProbe.mark("callList");}
    @Inject(method={"enableLighting","disableLighting","enableLight","disableLight"},at=@At("RETURN"))
    private static void pcla$lighting(CallbackInfo ci){RgbGpuLightmap.lightingState();}
    @Inject(method={"enableTexture2D","disableTexture2D"},at=@At("RETURN"))
    private static void pcla$texture(CallbackInfo ci){RgbGpuLightmap.textureState();}
}

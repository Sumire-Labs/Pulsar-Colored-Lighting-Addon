package com.sumirelabs.pcla.mixin;

import com.sumirelabs.pcla.client.ClientLightmap;
import com.sumirelabs.pcla.client.RgbGpuLightmap;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class MixinDisplayLightmap {
    @Shadow @Final private int[] lightmapColors;
    @Shadow private Minecraft mc;
    @Shadow private float getNightVisionBrightness(EntityLivingBase entity,float partialTicks){throw new AssertionError();}
    @Inject(method="updateLightmap",at=@At("HEAD"))
    private void pcla$beginRaw(float partialTicks,CallbackInfo ci){RgbGpuLightmap.beginCapture();}
    @WrapOperation(method="updateLightmap",at=@At(value="INVOKE",target="Lnet/minecraft/world/WorldProvider;getLightmapColors(FFFF[F)V",remap=false))
    private void pcla$raw(net.minecraft.world.WorldProvider provider,float partial,float sun,float sky,float block,float[] colors,
                          Operation<Void> original,@Local(index=5) int index) {
        original.call(provider,partial,sun,sky,block,colors);RgbGpuLightmap.captureCell(index,colors);
    }
    // This invocation occurs only after all 256 colors, including gamma/night vision, are written.
    @Inject(method="updateLightmap",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/texture/DynamicTexture;updateDynamicTexture()V",shift=At.Shift.AFTER))
    private void pcla$capture(float partialTicks,CallbackInfo ci) {
        float night=mc.player!=null && mc.player.isPotionActive(MobEffects.NIGHT_VISION)?getNightVisionBrightness(mc.player,partialTicks):0;
        ClientLightmap.capture(lightmapColors,night);
        RgbGpuLightmap.completeCapture(lightmapColors,mc.gameSettings.gammaSetting,night);
    }
}

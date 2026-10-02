package com.sumirelabs.pcla.mixin;

import com.sumirelabs.pcla.client.EntityTint;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(GlStateManager.class)
public abstract class MixinEntityColor {
    @ModifyVariable(method="color(FFFF)V",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private static float pcla$red(float value) { return EntityTint.state().red(value); }
    @ModifyVariable(method="color(FFFF)V",at=@At("HEAD"),argsOnly=true,ordinal=1)
    private static float pcla$green(float value) { return EntityTint.state().green(value); }
    @ModifyVariable(method="color(FFFF)V",at=@At("HEAD"),argsOnly=true,ordinal=2)
    private static float pcla$blue(float value) { return EntityTint.state().blue(value); }
    @ModifyVariable(method="color(FFFF)V",at=@At("HEAD"),argsOnly=true,ordinal=3)
    private static float pcla$alpha(float value) { return EntityTint.state().alpha(value); }
}

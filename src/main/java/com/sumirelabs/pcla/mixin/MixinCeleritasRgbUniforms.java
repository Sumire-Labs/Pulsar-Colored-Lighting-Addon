package com.sumirelabs.pcla.mixin;

import com.sumirelabs.pcla.client.RgbGpuLightmap;
import org.lwjgl.opengl.*;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="org.embeddedt.embeddium.impl.render.chunk.ShaderChunkRenderer",remap=false)
public abstract class MixinCeleritasRgbUniforms {
    @Unique private int pcla$rawBinding;
    @Inject(method="begin",at=@At("HEAD"),remap=false)
    private void pcla$save(CallbackInfo ci) {
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);GlStateManager.setActiveTexture(GL13.GL_TEXTURE3);
        pcla$rawBinding=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);GlStateManager.setActiveTexture(active);
    }
    @Inject(method="begin",at=@At("TAIL"),remap=false)
    private void pcla$uniforms(CallbackInfo ci) {
        if(RgbGpuLightmap.ready())RgbGpuLightmap.uniforms(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
    }
    @Inject(method="end",at=@At("TAIL"),remap=false)
    private void pcla$restore(CallbackInfo ci) {
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);GlStateManager.setActiveTexture(GL13.GL_TEXTURE3);
        GlStateManager.bindTexture(pcla$rawBinding);GlStateManager.setActiveTexture(active);
    }
}

package com.sumirelabs.pcla.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pcla.client.RgbGpuLightmap;
import net.minecraft.client.renderer.*;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(WorldVertexBufferUploader.class)
public abstract class MixinRgbBufferDraw {
    @WrapMethod(method="draw(Lnet/minecraft/client/renderer/BufferBuilder;)V")
    private void pcla$draw(BufferBuilder buffer,Operation<Void> original) {
        com.sumirelabs.pcla.client.RenderStateProbe.buffer(buffer.getVertexFormat());
        if(!buffer.getVertexFormat().hasUvOffset(1)){original.call(buffer);return;}
        // Record geometry only: the terrain/entity draw scope binds the GPU program when the list executes.
        if(org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_LIST_INDEX)!=0){original.call(buffer);return;}
        try(var scope=RgbGpuLightmap.terrain()){original.call(buffer);}
    }
}

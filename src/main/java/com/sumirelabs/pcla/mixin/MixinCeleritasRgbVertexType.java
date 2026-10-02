package com.sumirelabs.pcla.mixin;

import com.sumirelabs.pcla.client.CeleritasWideRgbVertex;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.impl.VanillaLikeChunkVertex;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="org.taumc.celeritas.impl.render.terrain.CeleritasWorldRenderer",remap=false)
public abstract class MixinCeleritasRgbVertexType {
    @Inject(method="chooseVertexType",at=@At("RETURN"),cancellable=true,remap=false)
    private void pcla$wide(CallbackInfoReturnable<ChunkVertexType> cir) {
        if(cir.getReturnValue() instanceof VanillaLikeChunkVertex)cir.setReturnValue(CeleritasWideRgbVertex.INSTANCE);
    }
}

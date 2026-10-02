package com.sumirelabs.pcla.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pcla.Pcla;
import com.sumirelabs.pcla.client.BlockTint;
import com.sumirelabs.pcla.config.Feature;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.color.IBlockColor;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import org.embeddedt.embeddium.impl.model.quad.BakedQuadView;
import org.taumc.celeritas.impl.world.cloned.CeleritasBlockAccess;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.embeddedt.embeddium.impl.render.chunk.compile.buffers.ChunkModelBuilder;
import org.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import org.embeddedt.embeddium.impl.model.quad.properties.ModelQuadOrientation;
import org.embeddedt.embeddium.impl.model.light.data.QuadLightData;
import net.minecraft.util.math.Vec3d;

/** Optional vintage renderer adapter; the shared sampler owns the color calculation. */
@Pseudo
@Mixin(targets="org.taumc.celeritas.impl.render.terrain.compile.pipeline.VintageBlockRenderer",remap=false)
public abstract class MixinCeleritasBlockTint {
    @Shadow private IBlockState currentState;
    @Shadow private CeleritasBlockAccess currentBlockAccess;
    @Unique private BlockTint.Context pcla$colors;
    @Shadow @Final private ChunkVertexEncoder.Vertex[] vertices;

    @Inject(method="writeGeometry",at=@At(value="INVOKE",target="Lorg/embeddedt/embeddium/impl/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push([Lorg/embeddedt/embeddium/impl/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;Lorg/embeddedt/embeddium/impl/render/chunk/terrain/material/Material;)V"),remap=false)
    private void pcla$light(int x,int y,int z,ChunkModelBuilder builder,Vec3d offset,Material material,BakedQuadView quad,int[] colors,QuadLightData light,ModelQuadOrientation orientation,CallbackInfo ci) {
        if(pcla$colors==null)return;
        for(int v=0;v<4;v++)vertices[v].light=BlockTint.coordinate(pcla$colors,orientation.getVertexIndex(v),vertices[v].light);
    }

    @WrapMethod(method="renderBlock",remap=false)
    private void pcla$scope(IBlockState state,BlockPos pos,CeleritasBlockAccess access,BlockRenderLayer layer,Operation<Void> original) {
        var previous=pcla$colors; pcla$colors=null;
        try { original.call(state,pos,access,layer); }
        finally { pcla$colors=previous; }
    }

    @Inject(method="getVertexColors",at=@At("RETURN"),remap=false)
    private void pcla$colors(BlockPos pos,IBlockColor provider,BakedQuadView quad,CallbackInfoReturnable<int[]> cir) {
        if(!BlockTint.enabled || !Pcla.colorsReady() || !com.sumirelabs.pcla.client.ClientDisplaySettings.celeritasAllowed(com.sumirelabs.pcla.client.ClientProxy.STORE.snapshot())) return;
        if(pcla$colors==null) pcla$colors=BlockTint.colorContext(currentBlockAccess,currentState,pos);
        BlockTint.applyColors(pcla$colors,(BakedQuad)(Object)quad,cir.getReturnValue());
    }
}

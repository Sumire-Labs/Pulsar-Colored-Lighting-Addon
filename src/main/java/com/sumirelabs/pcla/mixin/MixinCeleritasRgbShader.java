package com.sumirelabs.pcla.mixin;

import com.sumirelabs.pcla.client.RgbGpuLightmap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pinned Celeritas shader adapter; preserves its fragment, fog, alpha and AO correction code. */
@Pseudo
@Mixin(targets="org.embeddedt.embeddium.impl.render.shader.ShaderLoader",remap=false)
public abstract class MixinCeleritasRgbShader {
    @Inject(method="getShaderSource",at=@At("RETURN"),cancellable=true,remap=false)
    private static void pcla$shader(String name,CallbackInfoReturnable<String> cir) {
        if(name.equals("sodium:include/chunk_vertex.glsl")) {
            String source=cir.getReturnValue(),marker="#ifdef USE_VERTEX_COMPRESSION";
            if(!source.contains(marker))throw new IllegalStateException("Unsupported Celeritas vertex shader layout");
            String wide="""
                #if defined(PCLA_WIDE_LIGHT_COORD)
                in vec3 a_PosId;
                in vec4 a_Color;
                in vec2 a_TexCoord;
                in ivec2 a_LightCoord;
                in uvec2 pcla_MaterialDraw;
                in vec4 a_RdhFactor;
                void _vert_init() {
                    _vert_position=a_PosId;
                    _vert_tex_diffuse_coord=a_TexCoord;
                    _vert_tex_light_coord=a_LightCoord;
                    _vert_color=a_Color;
                    _vert_rdh_factor=a_RdhFactor;
                    _draw_id=pcla_MaterialDraw.y;
                    _material_params=pcla_MaterialDraw.x;
                }
                #elif defined(USE_VERTEX_COMPRESSION)
                """;
            if(!source.contains("vec4 _vert_rdh_factor"))
                wide=wide.replace("in vec4 a_RdhFactor;","").replace("_vert_rdh_factor=a_RdhFactor;","");
            cir.setReturnValue(source.replace(marker,wide));return;
        }
        if(!name.equals("sodium:blocks/block_layer_opaque.vsh"))return;
        String marker="vec4 _sample_lightmap(sampler2D lightMap, ivec2 uv) {";
        String source=cir.getReturnValue();
        if(!source.contains(marker))throw new IllegalStateException("Unsupported Celeritas lightmap shader layout");
        String functions=RgbGpuLightmap.source("rgb_lightmap.glsl").replace("texture2D(","texture(");
        cir.setReturnValue(source.replace(marker,functions+"\n"+marker+"\n    if(pcla_encoded(vec2(uv)))return vec4(pcla_light(vec2(uv)),1.0);\n"));
    }
}

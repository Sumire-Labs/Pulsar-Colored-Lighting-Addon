package com.sumirelabs.pcla.mixin;

import java.util.*;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;

/** The experimental renderer does not transform target classes at all unless explicitly enabled. */
public final class PclaMixinPlugin implements IMixinConfigPlugin {
    private final boolean enabled;
    private final boolean entities;
    private final boolean celeritas;
    public PclaMixinPlugin() { this(com.sumirelabs.pcla.config.RuntimeOptions.blockTint(),com.sumirelabs.pcla.config.RuntimeOptions.entityTint(),com.sumirelabs.pcla.config.RuntimeOptions.celeritasTint()); }
    PclaMixinPlugin(boolean enabled) { this(enabled,false); }
    PclaMixinPlugin(boolean enabled,boolean entities) { this(enabled,entities,false); }
    PclaMixinPlugin(boolean enabled,boolean entities,boolean celeritas) { this.enabled=enabled; this.entities=entities; this.celeritas=celeritas; }
    public void onLoad(String mixinPackage) {
        org.apache.logging.log4j.LogManager.getLogger("PCLA").info("Experimental block tint mixins enabled={}",enabled);
        org.apache.logging.log4j.LogManager.getLogger("PCLA").info("Experimental entity tint mixins enabled={}",entities);
        org.apache.logging.log4j.LogManager.getLogger("PCLA").info("Experimental Celeritas tint mixin enabled={}",enabled && celeritas);
    }
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String targetClassName,String mixinClassName) {
        if(Set.of("com.sumirelabs.pcla.mixin.MixinRgbTerrain","com.sumirelabs.pcla.mixin.MixinRgbBufferDraw",
                "com.sumirelabs.pcla.mixin.MixinRgbLightingState","com.sumirelabs.pcla.mixin.MixinRgbShutdown",
                "com.sumirelabs.pcla.mixin.MixinRgbTileEntity","com.sumirelabs.pcla.mixin.MixinCeleritasRgbShader",
                "com.sumirelabs.pcla.mixin.MixinCeleritasRgbUniforms","com.sumirelabs.pcla.mixin.MixinCeleritasRgbVertexType",
                "com.sumirelabs.pcla.mixin.MixinPulsarPaintingRgb","com.sumirelabs.pcla.mixin.MixinRgbHand").contains(mixinClassName))
            return (enabled || entities) && com.sumirelabs.pcla.config.RuntimeOptions.rgbLightmap();
        if(mixinClassName.equals("com.sumirelabs.pcla.mixin.MixinDisplayLightmap"))return enabled || entities;
        return enabled && celeritas && mixinClassName.equals("com.sumirelabs.pcla.mixin.MixinCeleritasBlockTint")
                || enabled && (mixinClassName.equals("com.sumirelabs.pcla.mixin.MixinBlockTint")
                || mixinClassName.equals("com.sumirelabs.pcla.mixin.MixinForgeBlockTint") || mixinClassName.equals("com.sumirelabs.pcla.mixin.MixinFluidTint"))
                || entities && Set.of("com.sumirelabs.pcla.mixin.MixinEntityTint","com.sumirelabs.pcla.mixin.MixinEntityColor",
                "com.sumirelabs.pcla.mixin.MixinEntityNameTint","com.sumirelabs.pcla.mixin.MixinPaintingTint").contains(mixinClassName);
    }
    public void acceptTargets(Set<String> myTargets,Set<String> otherTargets) {}
    public List<String> getMixins() { return null; }
    public void preApply(String name,ClassNode node,String mixin,IMixinInfo info) {}
    public void postApply(String name,ClassNode node,String mixin,IMixinInfo info) {}
}

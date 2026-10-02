package com.sumirelabs.pcla.mixin;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PclaMixinPluginTest {
    @Test void celeritasHookRequiresBothExplicitRendererFlags() {
        for(boolean block:new boolean[]{false,true}) for(boolean celeritas:new boolean[]{false,true})
            assertEquals(block && celeritas,new PclaMixinPlugin(block,false,celeritas)
                    .shouldApplyMixin("target","com.sumirelabs.pcla.mixin.MixinCeleritasBlockTint"));
    }
    @Test void entityAndBlockHooksAreIndependentlyGated() {
        for(boolean block:new boolean[]{false,true}) for(boolean entity:new boolean[]{false,true}) {
            var plugin=new PclaMixinPlugin(block,entity);
            assertEquals(block || entity,plugin.shouldApplyMixin("target","com.sumirelabs.pcla.mixin.MixinDisplayLightmap"));
            assertEquals(block,plugin.shouldApplyMixin("target","com.sumirelabs.pcla.mixin.MixinBlockTint"));
            for(String hook:new String[]{"MixinEntityTint","MixinEntityColor","MixinEntityNameTint","MixinPaintingTint"})
                assertEquals(entity,plugin.shouldApplyMixin("target","com.sumirelabs.pcla.mixin."+hook));
        }
    }
    @Test void onlyExplicitlyEnabledKnownRenderHooksMayTransformClasses() {
        var disabled=new PclaMixinPlugin(false); var enabled=new PclaMixinPlugin(true);
        for(String hook:new String[]{"MixinBlockTint","MixinForgeBlockTint","MixinFluidTint"}) {
            assertFalse(disabled.shouldApplyMixin("target","com.sumirelabs.pcla.mixin."+hook));
            assertTrue(enabled.shouldApplyMixin("target","com.sumirelabs.pcla.mixin."+hook));
        }
        assertFalse(enabled.shouldApplyMixin("target","unrelated.Mixin"));
    }
}

package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.client.EntityRenderRules;
import com.sumirelabs.pcla.config.*;
import java.io.StringReader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityRenderRulesTest {
    private static LightingConfig read(String json) {
        String body=json.substring(1,json.length()-1);
        return ConfigCodec.read(new StringReader("{\"schemaVersion\":1"+(body.isEmpty()?"":","+body)+"}"));
    }
    @Test void entityCategoryAndModVetoesApplyBeforeIndividualEnablement() {
        var defaults=read("{}");
        assertTrue(new EntityRenderRules(defaults).allowed("minecraft:sheep"));
        for(String json:new String[]{"{\"enabled\":false}","{\"features\":{\"ENTITY_RENDERING\":false}}",
                "{\"rules\":{\"ENTITY_RENDERING\":[{\"target\":\"minecraft:*\",\"mode\":\"OFF\"},{\"target\":\"minecraft:sheep\",\"mode\":\"ON\"}]}}"})
            assertFalse(new EntityRenderRules(read(json)).allowed("minecraft:sheep"));
    }
    @Test void individualExclusionsAreCachedWithoutAffectingOtherEntitiesOrSimulationProfile() {
        var config=read("{\"rules\":{\"ENTITY_RENDERING\":[{\"target\":\"minecraft:sheep\",\"mode\":\"OFF\"}]}}");
        var rules=new EntityRenderRules(config);
        for(int i=0;i<3;i++) { assertFalse(rules.allowed("minecraft:sheep")); assertTrue(rules.allowed("minecraft:painting")); }
        assertFalse(rules.allowed(null));
        assertEquals(read("{}").simulationProfile(),config.simulationProfile());
    }
}

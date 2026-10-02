package com.sumirelabs.pcla.config;

import com.sumirelabs.pcla.light.Rgb;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientRenderPolicyTest {
    private static LightingConfig config(boolean enabled,Set<Feature> features,Map<RuleKind,List<TargetRule>> rules){return new LightingConfig(enabled,features,rules);}
    private static TargetRule rule(String id,RuleMode mode){return new TargetRule(new Selector(id,Map.of()),mode,null);}
    @Test void remoteSimulationWinsWhileLocalRenderingOffStillWins() {
        var server=config(true,EnumSet.allOf(Feature.class),Map.of(RuleKind.EMISSION,List.of(new TargetRule(new Selector("minecraft:glowstone",Map.of()),RuleMode.ON,new Rgb(15,2,2)))));
        var local=config(true,EnumSet.of(Feature.BLOCK_RENDERING,Feature.ENTITY_RENDERING,Feature.CELERITAS),Map.of(
                RuleKind.EMISSION,List.of(new TargetRule(new Selector("minecraft:glowstone",Map.of()),RuleMode.ON,new Rgb(2,2,15))),
                RuleKind.BLOCK_RENDERING,List.of(rule("minecraft:stone",RuleMode.OFF)),RuleKind.ENTITY_RENDERING,List.of(rule("minecraft:zombie",RuleMode.OFF))));
        var policy=new ClientRenderPolicy(local,server);
        assertSame(server,policy.simulation());assertTrue(policy.simulation().feature(Feature.SKY_LIGHT));
        assertEquals(new Rgb(15,2,2),policy.simulation().resolve(RuleKind.EMISSION,"minecraft:glowstone",Map.of(),Rgb.WHITE).rgb());
        assertFalse(policy.allowed(RuleKind.BLOCK_RENDERING,"minecraft:stone",Map.of()));
        assertTrue(policy.allowed(RuleKind.BLOCK_RENDERING,"minecraft:quartz_block",Map.of()));
        assertFalse(policy.allowed(RuleKind.ENTITY_RENDERING,"minecraft:zombie",Map.of()));assertTrue(policy.allowed(RuleKind.ENTITY_RENDERING,"minecraft:sheep",Map.of()));
        assertFalse(local.hasRgbLane()); // Its simulation switches did not silently veto remote display.
    }
    @Test void eitherMasterAndEitherRenderCategoryCanVeto() {
        for(boolean serverOn:List.of(false,true))for(boolean localOn:List.of(false,true))for(boolean serverRender:List.of(false,true))for(boolean localRender:List.of(false,true)) {
            var serverFeatures=EnumSet.allOf(Feature.class);var localFeatures=EnumSet.allOf(Feature.class);
            if(!serverRender)serverFeatures.remove(Feature.BLOCK_RENDERING);if(!localRender)localFeatures.remove(Feature.BLOCK_RENDERING);
            var policy=new ClientRenderPolicy(config(localOn,localFeatures,Map.of()),config(serverOn,serverFeatures,Map.of()));
            assertEquals(serverOn && localOn && serverRender && localRender,policy.allowed(RuleKind.BLOCK_RENDERING,"minecraft:stone",Map.of()));
        }
    }
    @Test void serverParentOffCannotBeOverriddenByClientSpecificOnAndViceVersa() {
        var a=config(true,EnumSet.allOf(Feature.class),Map.of(RuleKind.BLOCK_RENDERING,List.of(rule("example:*",RuleMode.OFF))));
        var b=config(true,EnumSet.allOf(Feature.class),Map.of(RuleKind.BLOCK_RENDERING,List.of(rule("example:lamp",RuleMode.ON))));
        assertFalse(new ClientRenderPolicy(a,b).allowed(RuleKind.BLOCK_RENDERING,"example:lamp",Map.of()));
        assertFalse(new ClientRenderPolicy(b,a).allowed(RuleKind.BLOCK_RENDERING,"example:lamp",Map.of()));
    }
    @Test void stateExclusionsAndCeleritasRemainIndependentFromOtherRendering() {
        var server=config(true,EnumSet.allOf(Feature.class),Map.of());var features=EnumSet.allOf(Feature.class);features.remove(Feature.CELERITAS);
        var rule=new TargetRule(new Selector("minecraft:stone",Map.of("variant","granite")),RuleMode.OFF,null);
        var policy=new ClientRenderPolicy(config(true,features,Map.of(RuleKind.BLOCK_RENDERING,List.of(rule))),server);
        assertFalse(policy.feature(Feature.CELERITAS));assertTrue(policy.feature(Feature.BLOCK_RENDERING));
        assertFalse(policy.allowed(RuleKind.BLOCK_RENDERING,"minecraft:stone",Map.of("variant","granite")));
        assertTrue(policy.allowed(RuleKind.BLOCK_RENDERING,"minecraft:stone",Map.of("variant","stone")));
        assertThrows(IllegalArgumentException.class,()->policy.feature(Feature.BLOCK_LIGHT));
        assertThrows(IllegalArgumentException.class,()->policy.allowed(RuleKind.EMISSION,"minecraft:torch",Map.of()));
    }
    @Test void renderOnlyChangesHaveDistinctSettingsProfilesButIdenticalSimulationProfiles() {
        var a=config(true,EnumSet.allOf(Feature.class),Map.of());var b=config(true,EnumSet.allOf(Feature.class),Map.of(RuleKind.ENTITY_RENDERING,List.of(rule("minecraft:painting",RuleMode.OFF))));
        assertEquals(a.simulationProfile(),b.simulationProfile());assertNotEquals(a.settingsProfile(),b.settingsProfile());
    }
}

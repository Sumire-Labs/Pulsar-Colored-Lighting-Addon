package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.config.*;
import com.sumirelabs.pcla.light.Rgb;
import io.netty.buffer.Unpooled;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerSettingsTest {
    private static LightingConfig defaults(){return new LightingConfig(true,EnumSet.allOf(Feature.class),Map.of());}
    private static ServerSettings frame(UUID id,long epoch,int dimension,int protocol,LightingConfig cfg){return new ServerSettings(id,epoch,dimension,protocol,cfg.simulationProfile(),cfg);}
    private static ServerSettings roundtrip(ServerSettings settings){var b=Unpooled.buffer();try{ServerSettingsCodec.encode(settings,b);return ServerSettingsCodec.decode(b);}finally{b.release();}}
    @Test void allRulesFlagsAndRawDisabledFeaturesRoundTrip() {
        var rules=new EnumMap<RuleKind,List<TargetRule>>(RuleKind.class);
        rules.put(RuleKind.EMISSION,List.of(new TargetRule(new Selector("example:lamp",Map.of("lit","true")),RuleMode.ON,new Rgb(15,2,2))));
        rules.put(RuleKind.FILTER,List.of(new TargetRule(new Selector("minecraft:stained_glass",Map.of("color","blue")),RuleMode.INHERIT,new Rgb(1,2,14))));
        rules.put(RuleKind.BLOCK_RENDERING,List.of(new TargetRule(new Selector("example:*",Map.of()),RuleMode.OFF,null)));
        rules.put(RuleKind.ENTITY_RENDERING,List.of(new TargetRule(new Selector("minecraft:zombie",Map.of()),RuleMode.OFF,null)));
        for(boolean enabled:List.of(false,true))for(int protocol=1;protocol<=4;protocol++) {
            var cfg=new LightingConfig(enabled,EnumSet.allOf(Feature.class),rules);var input=frame(UUID.randomUUID(),9,-1,protocol,cfg);var output=roundtrip(input);
            assertEquals(input.session(),output.session());assertEquals(input.epoch(),output.epoch());assertEquals(input.dimension(),output.dimension());assertEquals(protocol,output.protocol());
            assertEquals(cfg.configuredFeatures(),output.config().configuredFeatures());assertEquals(cfg.settingsProfile(),output.config().settingsProfile());
            for(var kind:RuleKind.values())assertEquals(cfg.rules(kind),output.config().rules(kind));assertTrue(output.matches(output.hello()));
        }
    }
    @Test void settingsAndHelloMustMatchBeforeBinding() {
        var state=new SettingsSession();var cfg=defaults();var id=UUID.randomUUID();var settings=frame(id,1,0,4,cfg);
        assertNull(state.forHello(settings.hello()));assertTrue(state.accept(roundtrip(settings),0));assertNotNull(state.forHello(settings.hello()));
        assertNull(state.forHello(RegionFrame.helloV4(UUID.randomUUID(),1,0,cfg.simulationProfile())));
        assertNull(state.forHello(RegionFrame.helloV3(id,1,0,cfg.simulationProfile())));
        assertNull(state.forHello(RegionFrame.helloV4(id,1,-1,cfg.simulationProfile())));
        assertNull(state.forHello(RegionFrame.helloV4(id,1,0,"a".repeat(64))));
    }
    @Test void worldRetirementAndConnectionResetCannotReuseOldProfile() {
        var state=new SettingsSession();var cfg=defaults();var first=frame(UUID.randomUUID(),1,0,4,cfg);state.accept(first,0);state.retire(1);
        assertFalse(state.accept(first,0));assertNull(state.forHello(first.hello()));
        var second=frame(UUID.randomUUID(),2,-1,4,new LightingConfig(false,EnumSet.allOf(Feature.class),Map.of()));
        assertFalse(state.accept(second,0));assertTrue(state.accept(second,-1));assertFalse(state.forHello(second.hello()).config().enabled());
        state.clear();var nextConnection=frame(UUID.randomUUID(),1,0,4,cfg);assertTrue(state.accept(nextConnection,0));assertNull(state.forHello(second.hello()));
    }
    @Test void duplicateEpochCannotReplaceRenderingPolicyOrSession() {
        var state=new SettingsSession();var id=UUID.randomUUID();var cfg=defaults();var first=frame(id,2,0,4,cfg);assertTrue(state.accept(first,0));assertTrue(state.accept(roundtrip(first),0));
        var rules=Map.of(RuleKind.BLOCK_RENDERING,List.of(new TargetRule(new Selector("minecraft:stone",Map.of()),RuleMode.OFF,null)));
        var changed=new LightingConfig(true,EnumSet.allOf(Feature.class),rules);assertEquals(cfg.simulationProfile(),changed.simulationProfile());
        assertFalse(state.accept(frame(id,2,0,4,changed),0));assertFalse(state.accept(frame(UUID.randomUUID(),2,0,4,cfg),0));
        assertFalse(state.accept(frame(id,1,0,4,cfg),0));assertEquals(cfg.settingsProfile(),state.forHello(first.hello()).config().settingsProfile());
    }
    @Test void malformedFlagsProfilesCountsLengthsAndEpochsAreRejected() {
        var valid=frame(UUID.randomUUID(),1,0,4,defaults());
        for(int scenario=0;scenario<8;scenario++) {
            var b=Unpooled.buffer();try {
                ServerSettingsCodec.encode(valid,b);
                switch(scenario){case 0->b.setByte(1,5);case 1->b.setLong(18,0);case 2->b.setByte(30,'!');case 3->b.setByte(94,2);case 4->b.setInt(95,1<<16);case 5->b.setShort(99,1025);case 6->b.writeByte(0);default->b.writerIndex(b.writerIndex()-1);}
                assertThrows(IllegalArgumentException.class,()->ServerSettingsCodec.decode(b));
            }finally{b.release();}
        }
        var big=Unpooled.buffer(ServerSettingsCodec.MAX_BYTES+1);try{big.writeZero(ServerSettingsCodec.MAX_BYTES+1);assertThrows(IllegalArgumentException.class,()->ServerSettingsCodec.decode(big));}finally{big.release();}
    }
    @Test void outgoingRulesAndStringsAreBoundedToo() {
        var rules=new ArrayList<TargetRule>();for(int i=0;i<=ServerSettingsCodec.MAX_RULES;i++)rules.add(new TargetRule(new Selector("test:lamp_"+i,Map.of()),RuleMode.OFF,null));
        assertThrows(IllegalArgumentException.class,()->ServerSettingsCodec.validate(new LightingConfig(true,EnumSet.allOf(Feature.class),Map.of(RuleKind.EMISSION,rules))));
        var longRule=new TargetRule(new Selector("test:"+"a".repeat(300),Map.of()),RuleMode.OFF,null);
        assertThrows(IllegalArgumentException.class,()->ServerSettingsCodec.validate(new LightingConfig(true,EnumSet.allOf(Feature.class),Map.of(RuleKind.EMISSION,List.of(longRule)))));
    }
    @Test void configCapabilityIsConnectionScopedAndDoesNotReplaceLightCapabilities() {
        var peers=new PeerChannels<Object>();var connection=new Object();peers.registration(connection,"REGISTER",Set.of(PeerChannels.SETTINGS,PeerChannels.V4));
        assertTrue(peers.supportsSettings(connection));assertTrue(peers.supportsV4(connection));
        peers.registration(connection,"UNREGISTER",Set.of(PeerChannels.SETTINGS));assertFalse(peers.supportsSettings(connection));assertTrue(peers.supportsV4(connection));
        peers.registration(connection,"REGISTER",Set.of(PeerChannels.SETTINGS));peers.disconnected(connection);assertFalse(peers.supportsSettings(connection));
    }
}

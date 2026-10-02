package com.sumirelabs.pcla.config;

import com.sumirelabs.pcla.light.*;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaletteAuditTest {
    private static LightingConfig config(String rules){return ConfigCodec.read(new StringReader("{\"schemaVersion\":1,\"rules\":"+rules+"}"));}
    private static CompiledColorTable.State<Object> state(String id,Map<String,String> values){return new CompiledColorTable.State<>(new Object(),id,values);}
    @Test void tc6CatalogUsesVerifiedRegistrySuffixesAndKeepsIntensity() {
        String[] dyes={"white","orange","magenta","lightblue","yellow","lime","pink","gray","silver","cyan","purple","blue","brown","green","red","black"};
        for(String dye:dyes)for(String family:new String[]{"candle_","nitor_"}) {
            String id="thaumcraft:"+family+dye;assertTrue(ColorDefaults.sources().containsKey(id));
            var ratio=ColorDefaults.emission(id);assertTrue(ratio.max()>0);
            for(int level=0;level<16;level++)assertEquals(level,ratio.atIntensity(level).max());
        }
        assertFalse(ColorDefaults.sources().containsKey("thaumcraft:candle_light_blue"));
        assertFalse(ColorDefaults.sources().containsKey("Thaumcraft:blockCandle"));
        assertEquals(0xe44,ColorDefaults.emission("thaumcraft:candle_red").atIntensity(14).packed());
        assertEquals(0x35f,ColorDefaults.emission("thaumcraft:nitor_blue").atIntensity(15).packed());
        assertEquals(Rgb.WHITE,ColorDefaults.emission("thaumcraft:nitor_black").atIntensity(15));
        assertThrows(UnsupportedOperationException.class,()->ColorDefaults.sources().clear());
    }
    @Test void allDefaultsStayDarkAtZeroAndCompileLikeResolverAtEveryIntensity() {
        var cfg=config("{}");var resolver=new ColorResolver(cfg);
        var definitions=ColorDefaults.sources().keySet().stream().map(id->state(id,Map.of())).toList();
        var table=CompiledColorTable.compile(cfg,definitions);
        for(var state:definitions)for(int level=0;level<16;level++) {
            int compiled=table.get(state.identity()).emission(level);
            assertEquals(resolver.emission(state.registryId(),state.properties(),level).packed(),compiled);
            assertEquals(level,Rgb.unpack(compiled).max());
        }
        assertEquals(Rgb.WHITE,ColorDefaults.emission("notinstalled:lamp"));
    }
    @Test void reportSeparatesMissingModsBadStatesAndParentVetoes() {
        var cfg=config("""
          {"EMISSION":[{"target":"thaumcraft:*","mode":"OFF"},
            {"target":"thaumcraft:candle_blue","mode":"ON","rgb":[15,0,0]},
            {"target":"missing:lamp","rgb":[0,15,0]}],
           "FILTER":[{"target":"minecraft:stained_glass","state":{"colour":"blue"},"mode":"OFF"}],
           "ENTITY_RENDERING":[{"target":"minecraft:player","mode":"OFF"}]}
          """);
        var states=List.of(state("thaumcraft:candle_blue",Map.of()),state("minecraft:stained_glass",Map.of("color","blue")));
        var report=PaletteAudit.inspect(cfg,states,Set.of("minecraft:player","minecraft:sheep"));
        assertEquals(1,report.rules().get(0).matches());assertEquals(0,report.rules().get(0).enabledMatches());
        assertEquals(1,report.rules().get(1).matches());assertEquals(0,report.rules().get(1).enabledMatches());
        assertEquals("target_not_registered",report.rules().get(2).status());
        assertEquals("no_matching_state",report.rules().get(3).status());
        assertEquals("entity_ids",report.rules().get(4).unit());assertEquals(1,report.rules().get(4).matches());
        var candle=report.sources().stream().filter(s->s.id().equals("thaumcraft:candle_blue")).findFirst().orElseThrow();
        assertEquals(Set.of(0xfff),candle.resolvedRgbAt15());
    }
    @Test void registryAuditDoesNotNeedOptionalModsAndReportsStateValues() {
        var report=PaletteAudit.inspect(config("{}"),List.of(state("minecraft:lit_furnace",Map.of("facing","north")),state("minecraft:lit_furnace",Map.of("facing","south"))),Set.of());
        var furnace=report.sources().stream().filter(s->s.id().equals("minecraft:lit_furnace")).findFirst().orElseThrow();
        assertEquals(2,furnace.registeredStates());assertEquals(Set.of("north","south"),furnace.properties().get("facing"));
        assertTrue(report.sources().stream().filter(s->s.id().startsWith("thaumcraft:")).allMatch(s->s.registeredStates()==0));
    }
    @Test void shippedExamplesParseAndEnforceSeparateSimulationAndDisplayChoices()throws Exception {
        LightingConfig custom,off;
        try(var reader=Files.newBufferedReader(Path.of("docs/examples/pcla-tc6-custom.json"))){custom=ConfigCodec.read(reader);}
        try(var reader=Files.newBufferedReader(Path.of("docs/examples/pcla-thaumcraft-off.json"))){off=ConfigCodec.read(reader);}
        var resolver=new ColorResolver(custom);
        assertEquals(0x2e3,resolver.emission("thaumcraft:candle_blue",Map.of(),14).packed());
        assertEquals(0xfff,resolver.emission("thaumcraft:nitor_red",Map.of(),15).packed());
        assertFalse(custom.resolve(RuleKind.BLOCK_RENDERING,"thaumcraft:candle_blue",Map.of(),null).enabled());
        assertTrue(custom.resolve(RuleKind.BLOCK_RENDERING,"thaumcraft:nitor_red",Map.of(),null).enabled());
        for(var kind:RuleKind.values())assertFalse(off.resolve(kind,"thaumcraft:nitor_blue",Map.of(),Rgb.WHITE).enabled());
        assertEquals(ColorDefaults.emission("minecraft:torch").atIntensity(14),new ColorResolver(off).emission("minecraft:torch",Map.of(),14));
    }
    @Test void categoryAndIndividualVetoesStillApplyToNewSources() {
        for(String id:List.of("thaumcraft:candle_red","thaumcraft:nitor_blue","minecraft:sea_lantern")) {
            var cfg=config("{\"EMISSION\":[{\"target\":\""+id+"\",\"mode\":\"OFF\"}]}");
            assertEquals(0xaaa,new ColorResolver(cfg).emission(id,Map.of(),10).packed());
            var disabled=new LightingConfig(true,EnumSet.of(Feature.SKY_LIGHT),Map.of());
            assertEquals(0xaaa,new ColorResolver(disabled).emission(id,Map.of(),10).packed());
        }
    }
}

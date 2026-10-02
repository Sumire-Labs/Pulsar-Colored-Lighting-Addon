package com.sumirelabs.pcla.light;

import com.sumirelabs.pcla.config.*;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompiledColorTableTest {
    private static LightingConfig read(String fields) {
        return ConfigCodec.read(new StringReader("{\"schemaVersion\":1," + fields + "}"));
    }

    @Test void compiledTablesMatchResolverForAllFeatureCombinationsAndLightLevels() {
        var states = List.of(
                new CompiledColorTable.State<>(new Object(), "minecraft:torch", Map.<String, String>of()),
                new CompiledColorTable.State<>(new Object(), "minecraft:stained_glass", Map.of("color", "red")),
                new CompiledColorTable.State<>(new Object(), "minecraft:stained_glass_pane", Map.of("color", "blue")),
                new CompiledColorTable.State<>(new Object(), "unknown:lamp", Map.<String, String>of()));
        for (int mask = 0; mask < 1 << Feature.values().length; mask++) {
            var features = EnumSet.noneOf(Feature.class);
            for (Feature f : Feature.values()) if ((mask & 1 << f.ordinal()) != 0) features.add(f);
            for (boolean enabled : new boolean[]{false, true}) {
                var config = new LightingConfig(enabled, features, Map.of());
                var resolver = new ColorResolver(config);
                var table = CompiledColorTable.compile(config, states);
                for (var state : states) {
                    var compiled = table.get(state.identity());
                    for (int level = 0; level <= 15; level++) {
                        assertEquals(resolver.emission(state.registryId(), state.properties(), level).packed(),
                                compiled.emission(level));
                        assertEquals(resolver.absorption(state.registryId(), state.properties(), level).packed(),
                                compiled.absorption(level));
                    }
                    assertEquals(0xfff, compiled.absorption(255));
                }
            }
        }
    }

    @Test void keepsExactRatiosBeforeScalingAndNeverFreezesContextualEmission() {
        for (int packed = 1; packed <= 0xfff; packed++) {
            Rgb ratio = Rgb.unpack(packed);
            var compiled = new CompiledColor(ratio, Rgb.BLACK);
            for (int level = 0; level < 16; level++)
                assertEquals(ratio.atIntensity(level).packed(), compiled.emission(level));
        }
        var config = read("\"rules\":{\"EMISSION\":[{\"target\":\"test:lamp\",\"rgb\":[7,9,0]}]}");
        var lamp = new Object();
        var color = CompiledColorTable.compile(config,
                List.of(new CompiledColorTable.State<>(lamp, "test:lamp", Map.of()))).get(lamp);
        assertEquals(0x790, color.emission(9));
        assertEquals(0, color.emission(0));
        assertEquals(0x230, color.emission(3));
        assertEquals(0x790, color.emission(9));
    }

    @Test void offRulesAndStateOverridesSurviveCompilation() {
        var config = read("""
                "rules": {
                  "EMISSION": [
                    {"target":"test:*", "mode":"OFF"},
                    {"target":"test:lamp", "state":{"lit":"true"}, "mode":"ON", "rgb":[15,0,0]}
                  ],
                  "FILTER": [
                    {"target":"test:lamp", "rgb":[15,0,0]},
                    {"target":"test:lamp", "state":{"lit":"true"}, "rgb":[0,15,0]}
                  ]
                }
                """);
        Object lit = new Object(), unlit = new Object();
        var table = CompiledColorTable.compile(config, List.of(
                new CompiledColorTable.State<>(lit, "test:lamp", Map.of("lit", "true")),
                new CompiledColorTable.State<>(unlit, "test:lamp", Map.of("lit", "false"))));
        assertEquals(0x999, table.get(lit).emission(9));
        assertEquals(0xf3f, table.get(lit).absorption(3));
        assertEquals(0x3ff, table.get(unlit).absorption(3));
        assertEquals(0xfff, table.get(lit).absorption(255));
        assertEquals(0xf0f, table.get(lit).absorption(0));
    }

    @Test void sharesProfilesButKeepsDistinctStateIdentitiesEvenIfTheyCompareEqual() {
        Object red = new String("same"), blue = new String("same"), anotherRed = new Object();
        var table = CompiledColorTable.compile(read("\"enabled\":true"), List.of(
                new CompiledColorTable.State<>(red, "minecraft:stained_glass", Map.of("color", "red")),
                new CompiledColorTable.State<>(blue, "minecraft:stained_glass", Map.of("color", "blue")),
                new CompiledColorTable.State<>(anotherRed, "minecraft:stained_glass_pane", Map.of("color", "red"))));
        assertEquals(3, table.stateCount());
        assertEquals(2, table.profileCount());
        assertSame(table.get(red), table.get(anotherRed));
        assertNotSame(table.get(red), table.get(blue));
        assertEquals(0, table.get(new String("same")).absorption(0));
        assertEquals(0x777, table.get(new Object()).emission(7));
        assertEquals(0x333, table.get(new Object()).absorption(3));
    }

    @Test void snapshotsInputsAndDeduplicatesOnlyMatchingCanonicalStateSelectors() {
        var properties = new HashMap<>(Map.of("color", "red"));
        var state = new CompiledColorTable.State<>(new Object(), "minecraft:stained_glass", properties);
        properties.put("color", "blue");
        var states = new ArrayList<>(List.of(state));
        var config = read("\"enabled\":true");
        var table = CompiledColorTable.compile(config, states);
        states.clear();
        assertEquals(0x1dd, table.get(state.identity()).absorption(0));
        var repeated=new CompiledColorTable.State<>(state.identity(),state.registryId(),Map.of("color","red"));
        assertEquals(1,CompiledColorTable.compile(config,List.of(state,repeated)).stateCount());
        assertThrows(IllegalArgumentException.class, () -> CompiledColorTable.compile(config, List.of(state,
            new CompiledColorTable.State<>(state.identity(),state.registryId(),Map.of("color","blue")))));
        assertThrows(IllegalArgumentException.class, () -> CompiledColorTable.compile(config, List.of(state,
            new CompiledColorTable.State<>(state.identity(),"minecraft:torch",state.properties()))));
    }

    @Test void rejectsInvalidLightValuesWithoutWrappingOrChangingSkyMinimum() {
        var neutral = CompiledColorTable.compile(read("\"enabled\":false"),
                List.<CompiledColorTable.State<Object>>of()).get(new Object());
        assertThrows(IllegalArgumentException.class, () -> neutral.emission(-1));
        assertThrows(IllegalArgumentException.class, () -> neutral.emission(16));
        assertThrows(IllegalArgumentException.class, () -> neutral.absorption(-1));
        assertEquals(0, neutral.absorption(0));
        assertEquals(0x111, neutral.absorption(1));
        assertEquals(0xfff, neutral.absorption(Integer.MAX_VALUE));
    }
}

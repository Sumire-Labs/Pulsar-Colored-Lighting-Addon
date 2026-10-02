package com.sumirelabs.pcla.config;

import com.sumirelabs.pcla.light.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LightingConfigTest {
    static LightingConfig read(String fields) { return ConfigCodec.read(new StringReader("{\"schemaVersion\":1," + fields + "}")); }
    static String rule(String id, String mode, String extra) { return "{\"target\":\"" + id + "\",\"mode\":\"" + mode + "\"" + extra + "}"; }

    @Test void disablingAColorPreservesTheOriginalLight() {
        var c = read("\"rules\":{\"EMISSION\":[" + rule("minecraft:torch", "OFF", "") + "]}");
        assertEquals(Rgb.white(14), new ColorResolver(c).emission("minecraft:torch", Map.of(), 14));
        assertEquals(Rgb.BLACK, new ColorResolver(c).emission("minecraft:torch", Map.of(), 0));
    }
    @Test void parentOffCannotBeBypassedBySpecificOn() {
        String specific = rule("thaumcraft:crystal_aer", "ON", ",\"rgb\":[15,0,0]");
        String parent = rule("thaumcraft:*", "OFF", "");
        for (String rules : List.of(parent + "," + specific, specific + "," + parent)) {
            var c = read("\"rules\":{\"EMISSION\":[" + rules + "]}");
            assertEquals(Rgb.white(10), new ColorResolver(c).emission("thaumcraft:crystal_aer", Map.of(), 10));
        }
    }
    @Test void mostSpecificColorWinsRegardlessOfFileOrder() {
        String block = rule("test:lamp", "INHERIT", ",\"rgb\":[0,15,0]");
        String state = rule("test:lamp", "INHERIT", ",\"state\":{\"lit\":\"true\"},\"rgb\":[15,0,0]");
        for (String rules : List.of(block + "," + state, state + "," + block)) {
            var resolver = new ColorResolver(read("\"rules\":{\"EMISSION\":[" + rules + "]}"));
            assertEquals(new Rgb(9, 0, 0), resolver.emission("test:lamp", Map.of("lit", "true"), 9));
            assertEquals(new Rgb(0, 9, 0), resolver.emission("test:lamp", Map.of("lit", "false"), 9));
        }
    }
    @Test void everyFeatureCombinationRespectsItsGates() {
        for (int mask = 0; mask < (1 << Feature.values().length); mask++) {
            Set<Feature> features = EnumSet.noneOf(Feature.class);
            for (Feature f : Feature.values()) if ((mask & (1 << f.ordinal())) != 0) features.add(f);
            for (boolean master : new boolean[]{true, false}) {
                var c = new LightingConfig(master, features, Map.of());
                var r = new ColorResolver(c);
                boolean block = master && features.contains(Feature.BLOCK_LIGHT);
                boolean sky = master && features.contains(Feature.SKY_LIGHT);
                assertEquals(block || sky, c.hasRgbLane());
                assertEquals(block, c.resolve(RuleKind.EMISSION, "minecraft:torch", Map.of(), Rgb.WHITE).enabled());
                assertEquals((block || sky) && features.contains(Feature.ENTITY_RENDERING),
                        c.resolve(RuleKind.ENTITY_RENDERING, "minecraft:pig", Map.of(), null).enabled());
                assertEquals((block || sky) && features.contains(Feature.BLOCK_RENDERING),
                        c.resolve(RuleKind.BLOCK_RENDERING, "minecraft:stone", Map.of(), null).enabled());
                for (String id : List.of("minecraft:stained_glass", "minecraft:stained_glass_pane")) {
                    Feature glassFeature = id.endsWith("_pane") ? Feature.STAINED_GLASS_PANES : Feature.STAINED_GLASS;
                    boolean filter = (block || sky) && features.contains(Feature.FILTERS) && features.contains(glassFeature);
                    assertEquals(filter ? new Rgb(1, 13, 13) : Rgb.BLACK, r.absorption(id, Map.of("color", "red"), 0));
                }
                if (!block) assertEquals(Rgb.white(14), r.emission("minecraft:torch", Map.of(), 14));
            }
        }
    }
    @Test void simulationProfileIgnoresRenderingButTracksLightRules() {
        var base = read("\"enabled\":true");
        assertEquals(base.simulationProfile(), read("\"features\":{\"ENTITY_RENDERING\":false,\"BLOCK_RENDERING\":false,\"CELERITAS\":false}").simulationProfile());
        assertNotEquals(base.simulationProfile(), read("\"features\":{\"SKY_LIGHT\":false}").simulationProfile());
        assertNotEquals(base.simulationProfile(), read("\"rules\":{\"EMISSION\":[" + rule("test:lamp", "OFF", "") + "]}").simulationProfile());
        String a = rule("test:a", "OFF", ""), b = rule("test:b", "OFF", "");
        assertEquals(read("\"rules\":{\"EMISSION\":[" + a + "," + b + "]}").simulationProfile(),
                read("\"rules\":{\"EMISSION\":[" + b + "," + a + "]}").simulationProfile());
    }
    @Test void rejectsTyposWrongTypesAndAmbiguousStates() {
        for (String fields : List.of("\"enabled\":\"false\"", "\"enabeld\":false", "\"features\":{\"SKYLIGHT\":false}",
                "\"rules\":{\"EMISSION\":[{\"target\":\"test:a\",\"rgb\":[1.2,2,3]}]}",
                "\"rules\":{\"EMISSION\":[{\"target\":\"test:a\",\"rgb\":[0,0,0]}]}",
                "\"rules\":{\"ENTITY_RENDERING\":[{\"target\":\"test:a\",\"state\":{\"x\":\"y\"}}]}",
                "\"rules\":{\"FILTER\":[{\"target\":\"test:a\",\"state\":{\"lit\":\"true\"}},{\"target\":\"test:a\",\"state\":{\"facing\":\"north\"}}]}"))
            assertThrows(IllegalArgumentException.class, () -> read(fields), fields);
    }
    @Test void startupSnapshotIsImmutable() {
        var features = EnumSet.allOf(Feature.class);
        var state = new HashMap<>(Map.of("lit", "true"));
        var selector = new Selector("test:lamp", state);
        var rules = new ArrayList<>(List.of(new TargetRule(selector, RuleMode.OFF, null)));
        var c = new LightingConfig(true, features, Map.of(RuleKind.EMISSION, rules));
        features.clear(); state.clear(); rules.clear();
        assertTrue(c.feature(Feature.BLOCK_LIGHT));
        assertEquals(Rgb.white(10), new ColorResolver(c).emission("test:lamp", Map.of("lit", "true"), 10));
    }
    @Test void unknownStatesRemainNeutralAndFiltersNeverReduceOrdinaryOpacity() {
        var resolver = new ColorResolver(read("\"enabled\":true"));
        assertEquals(Rgb.WHITE, ColorDefaults.filter("minecraft:stained_glass", Map.of()));
        assertEquals(Rgb.white(3), resolver.absorption("minecraft:water", Map.of(), 3));
        assertEquals(Rgb.white(15), resolver.absorption("minecraft:stained_glass", Map.of("color", "red"), 255));
        assertEquals(Rgb.white(7), resolver.emission("unknown:lamp", Map.of(), 7));
    }
    @Test void configFileCreatesDefaultsButPreservesInvalidUserFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pcla.json");
        assertTrue(ConfigFile.load(file).enabled());
        String invalid = "{\"schemaVersion\":999}";
        Files.writeString(file, invalid);
        assertThrows(IllegalStateException.class, () -> ConfigFile.load(file));
        assertEquals(invalid, Files.readString(file));
    }
}

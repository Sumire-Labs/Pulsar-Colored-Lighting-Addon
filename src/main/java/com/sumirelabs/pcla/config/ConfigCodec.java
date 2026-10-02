package com.sumirelabs.pcla.config;

import com.google.gson.*;
import com.sumirelabs.pcla.light.Rgb;
import java.io.Reader;
import java.util.*;

/** Strict schema parsing: misspelled switches must not silently enable a feature. */
public final class ConfigCodec {
    private ConfigCodec() {}
    public static LightingConfig read(Reader reader) {
        JsonObject root = object(new JsonParser().parse(reader), "root");
        only(root, Set.of("schemaVersion", "enabled", "features", "rules"), "root");
        if (!root.has("schemaVersion") || integer(root.get("schemaVersion"), "schemaVersion") != 1)
            throw new IllegalArgumentException("Unsupported or missing schemaVersion (expected 1)");
        boolean enabled = !root.has("enabled") || bool(root.get("enabled"), "enabled");
        EnumSet<Feature> features = EnumSet.allOf(Feature.class);
        if (root.has("features")) {
            JsonObject f = object(root.get("features"), "features");
            for (var e : f.entrySet()) {
                Feature feature = enumValue(Feature.class, e.getKey(), "feature");
                if (!bool(e.getValue(), "features." + e.getKey())) features.remove(feature);
            }
        }
        EnumMap<RuleKind, List<TargetRule>> rules = new EnumMap<>(RuleKind.class);
        if (root.has("rules")) {
            JsonObject categories = object(root.get("rules"), "rules");
            for (var category : categories.entrySet()) {
                RuleKind kind = enumValue(RuleKind.class, category.getKey(), "rule category");
                if (!category.getValue().isJsonArray()) throw new IllegalArgumentException(kind + " must be an array");
                List<TargetRule> parsed = new ArrayList<>();
                for (JsonElement value : category.getValue().getAsJsonArray()) parsed.add(rule(value));
                rules.put(kind, parsed);
            }
        }
        return new LightingConfig(enabled, features, rules);
    }
    private static TargetRule rule(JsonElement value) {
        JsonObject r = object(value, "rule");
        only(r, Set.of("target", "state", "mode", "rgb"), "rule");
        if (!r.has("target")) throw new IllegalArgumentException("Rule requires target");
        String id = string(r.get("target"), "target");
        Map<String, String> state = new TreeMap<>();
        if (r.has("state")) for (var e : object(r.get("state"), "state").entrySet())
            state.put(e.getKey(), string(e.getValue(), "state." + e.getKey()));
        RuleMode mode = r.has("mode") ? enumValue(RuleMode.class, string(r.get("mode"), "mode"), "mode") : RuleMode.INHERIT;
        Rgb rgb = null;
        if (r.has("rgb")) {
            JsonElement color = r.get("rgb");
            if (!color.isJsonArray() || color.getAsJsonArray().size() != 3) throw new IllegalArgumentException("rgb requires exactly three integers");
            JsonArray a = color.getAsJsonArray();
            rgb = new Rgb(integer(a.get(0), "red"), integer(a.get(1), "green"), integer(a.get(2), "blue"));
        }
        return new TargetRule(new Selector(id, state), mode, rgb);
    }
    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(name + " must be an object");
        return value.getAsJsonObject();
    }
    private static String string(JsonElement value, String name) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(name + " must be a string");
        return value.getAsString();
    }
    private static boolean bool(JsonElement value, String name) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(name + " must be true or false");
        return value.getAsBoolean();
    }
    private static int integer(JsonElement value, String name) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(name + " must be an integer");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException(name + " must be an integer", e); }
    }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String name) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown " + name + ": " + value, e); }
    }
    private static void only(JsonObject object, Set<String> allowed, String scope) {
        for (String key : object.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown " + scope + " property: " + key);
    }
}

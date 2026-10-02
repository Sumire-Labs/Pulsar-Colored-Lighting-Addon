package com.sumirelabs.pcla.config;

import com.sumirelabs.pcla.light.Rgb;
import com.sumirelabs.pcla.light.ColorDefaults;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Validated immutable startup settings. Resolution is for registry compilation, not per-voxel BFS. */
public final class LightingConfig {
    public record Decision(boolean enabled, Rgb rgb) {}
    private final boolean enabled;
    private final Set<Feature> features;
    private final Map<RuleKind, List<TargetRule>> rules;
    private final String simulationProfile;

    public LightingConfig(boolean enabled, Set<Feature> features, Map<RuleKind, List<TargetRule>> rules) {
        this.enabled = enabled;
        this.features = Set.copyOf(features);
        EnumMap<RuleKind, List<TargetRule>> copy = new EnumMap<>(RuleKind.class);
        for (RuleKind kind : RuleKind.values()) {
            List<TargetRule> list = List.copyOf(rules.getOrDefault(kind, List.of()));
            for (int i = 0; i < list.size(); i++) {
                TargetRule a = list.get(i);
                if ((kind == RuleKind.BLOCK_RENDERING || kind == RuleKind.ENTITY_RENDERING) && a.rgb() != null)
                    throw new IllegalArgumentException("Rendering rules cannot specify RGB");
                if (kind == RuleKind.ENTITY_RENDERING && !a.target().properties().isEmpty())
                    throw new IllegalArgumentException("Entity rules cannot specify block states");
                if (kind == RuleKind.EMISSION && a.rgb() != null && a.rgb().max() == 0)
                    throw new IllegalArgumentException("Emission colors must have a nonzero component; use OFF for ordinary white light");
                for (int j = 0; j < i; j++) {
                    Selector b = list.get(j).target();
                    if (a.target().specificity() == b.specificity() && a.target().overlaps(b))
                        throw new IllegalArgumentException("Ambiguous " + kind + " selectors: " + a.target().canonical() + " and " + b.canonical());
                }
            }
            copy.put(kind, list);
        }
        this.rules = Collections.unmodifiableMap(copy);
        this.simulationProfile = computeSimulationProfile();
    }

    public boolean enabled() { return enabled; }
    public Set<Feature> configuredFeatures() { return features; }
    public boolean feature(Feature feature) { return enabled && features.contains(feature); }
    public boolean hasRgbLane() { return feature(Feature.BLOCK_LIGHT) || feature(Feature.SKY_LIGHT); }
    public List<TargetRule> rules(RuleKind kind) { return rules.get(kind); }
    public String simulationProfile() { return simulationProfile; }
    /** Includes rendering restrictions too; simulationProfile intentionally does not. */
    public String settingsProfile() {
        StringBuilder text=new StringBuilder(simulationProfile);
        for(Feature feature:Feature.values())text.append(';').append(feature).append('=').append(features.contains(feature));
        for(RuleKind kind:RuleKind.values()){text.append(';').append(kind);rules.get(kind).stream().map(TargetRule::canonical).sorted().forEach(r->text.append(';').append(r));}
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }

    public Decision resolve(RuleKind kind, String registryId, Map<String, String> state, Rgb defaultRgb) {
        boolean category = switch (kind) {
            case EMISSION -> feature(Feature.BLOCK_LIGHT);
            case FILTER -> hasRgbLane() && feature(Feature.FILTERS);
            case BLOCK_RENDERING -> hasRgbLane() && feature(Feature.BLOCK_RENDERING);
            case ENTITY_RENDERING -> hasRgbLane() && feature(Feature.ENTITY_RENDERING);
        };
        if (!category) return new Decision(false, null);
        if (kind == RuleKind.FILTER && ((registryId.equals("minecraft:stained_glass") && !feature(Feature.STAINED_GLASS))
                || (registryId.equals("minecraft:stained_glass_pane") && !feature(Feature.STAINED_GLASS_PANES))))
            return new Decision(false, null);
        Rgb color = defaultRgb;
        int specificity = Integer.MIN_VALUE;
        for (TargetRule rule : rules.get(kind)) {
            if (!rule.target().matches(registryId, state)) continue;
            // An OFF at any matching parent scope vetoes a more specific ON.
            if (rule.mode() == RuleMode.OFF) return new Decision(false, null);
            if (rule.rgb() != null && rule.target().specificity() > specificity) {
                color = rule.rgb(); specificity = rule.target().specificity();
            }
        }
        return new Decision(true, color);
    }

    private String computeSimulationProfile() {
        // Defaults revision is part of the key: changing built-in colors must invalidate saved color data too.
        StringBuilder out = new StringBuilder("pcla-schema1;").append(ColorDefaults.signature()).append(';').append(enabled);
        for (Feature f : List.of(Feature.BLOCK_LIGHT, Feature.SKY_LIGHT, Feature.FILTERS, Feature.STAINED_GLASS, Feature.STAINED_GLASS_PANES))
            out.append(';').append(f).append('=').append(features.contains(f));
        for (RuleKind kind : List.of(RuleKind.EMISSION, RuleKind.FILTER)) {
            out.append(';').append(kind);
            rules.get(kind).stream().map(TargetRule::canonical).sorted().forEach(r -> out.append(';').append(r));
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}

package com.sumirelabs.pcla.light;

import com.sumirelabs.pcla.config.*;
import java.util.Map;

/** Configuration-level lookup; compile decisions per block state before attaching an engine. */
public final class ColorResolver {
    private final LightingConfig config;
    public ColorResolver(LightingConfig config) { this.config = config; }

    public Rgb emission(String id, Map<String, String> state, int originalEmission) {
        var decision = config.resolve(RuleKind.EMISSION, id, state, ColorDefaults.emission(id));
        return decision.enabled() ? decision.rgb().atIntensity(originalEmission) : Rgb.white(originalEmission);
    }

    public Rgb absorption(String id, Map<String, String> state, int originalOpacity) {
        if (originalOpacity < 0) throw new IllegalArgumentException("Negative opacity");
        int base = Math.min(15, originalOpacity);
        var decision = config.resolve(RuleKind.FILTER, id, state, ColorDefaults.filter(id, state));
        if (!decision.enabled()) return Rgb.white(base);
        Rgb transmission = decision.rgb();
        return new Rgb(Math.max(base, 15 - transmission.red()), Math.max(base, 15 - transmission.green()),
                Math.max(base, 15 - transmission.blue()));
    }
}

package com.sumirelabs.pcla.light;

import com.sumirelabs.pcla.config.LightingConfig;
import com.sumirelabs.pcla.config.RuleKind;
import java.util.*;

/** Resolves selectors once. State identity is independent of lossy legacy metadata IDs. */
public final class CompiledColorTable<S> {
    public record State<S>(S identity, String registryId, Map<String, String> properties) {
        public State {
            Objects.requireNonNull(identity, "state");
            Objects.requireNonNull(registryId, "registryId");
            properties = Map.copyOf(properties);
        }
    }

    private static final CompiledColor NEUTRAL = new CompiledColor(Rgb.WHITE, Rgb.BLACK);
    private final IdentityHashMap<S, CompiledColor> states;
    private final int profileCount;

    private CompiledColorTable(IdentityHashMap<S, CompiledColor> states, int profileCount) {
        this.states = states;
        this.profileCount = profileCount;
    }

    public static <S> CompiledColorTable<S> compile(LightingConfig config, Iterable<State<S>> definitions) {
        Objects.requireNonNull(config, "config");
        var states = new IdentityHashMap<S, CompiledColor>();
        var seen = new IdentityHashMap<S, State<S>>();
        var profiles = new HashMap<Integer, CompiledColor>();
        for (State<S> state : definitions) {
            var previous=seen.putIfAbsent(state.identity(),state);
            if(previous!=null) {
                // Extended state containers can list multiple wrappers with the same clean state.
                // Only an identical canonical selector may share that identity.
                if(!previous.registryId().equals(state.registryId()) || !previous.properties().equals(state.properties()))
                    throw new IllegalArgumentException("Conflicting selectors for state identity: "+previous.registryId()+" / "+state.registryId());
                continue;
            }
            var emission = config.resolve(RuleKind.EMISSION, state.registryId(), state.properties(),
                    ColorDefaults.emission(state.registryId()));
            var filter = config.resolve(RuleKind.FILTER, state.registryId(), state.properties(),
                    ColorDefaults.filter(state.registryId(), state.properties()));
            Rgb ratio = emission.enabled() ? emission.rgb() : Rgb.WHITE;
            Rgb transmission = filter.enabled() ? filter.rgb() : Rgb.WHITE;
            Rgb absorption = new Rgb(15 - transmission.red(), 15 - transmission.green(), 15 - transmission.blue());
            int key = ratio.packed() << 12 | absorption.packed();
            CompiledColor profile = profiles.computeIfAbsent(key, ignored -> new CompiledColor(ratio, absorption));
            states.put(state.identity(), profile);
        }
        return new CompiledColorTable<>(states, profiles.size());
    }

    /** Unknown states retain ordinary light. Engine caches may retain the returned immutable profile. */
    public CompiledColor get(S state) { return states.getOrDefault(state, NEUTRAL); }
    public int stateCount() { return states.size(); }
    public int profileCount() { return profileCount; }
}

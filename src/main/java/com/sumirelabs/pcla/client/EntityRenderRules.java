package com.sumirelabs.pcla.client;

import com.sumirelabs.pcla.config.*;
import java.util.*;

/** Immutable startup configuration, with decisions cached per registry ID. Client-thread use only. */
public final class EntityRenderRules {
    private final LightingConfig config;
    private final Map<String,Boolean> decisions=new HashMap<>();
    public EntityRenderRules(LightingConfig config) { this.config=Objects.requireNonNull(config); }
    public boolean allowed(String id) {
        return id!=null && decisions.computeIfAbsent(id,key->config.resolve(RuleKind.ENTITY_RENDERING,key,Map.of(),null).enabled());
    }
}

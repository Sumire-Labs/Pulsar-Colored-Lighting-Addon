package com.sumirelabs.pcla.config;

import java.util.*;

/** Server simulation is authoritative. Either endpoint can veto visual features and targets. */
public final class ClientRenderPolicy {
    private final LightingConfig local,server;
    public ClientRenderPolicy(LightingConfig local,LightingConfig server){this.local=Objects.requireNonNull(local);this.server=Objects.requireNonNull(server);}
    public LightingConfig simulation(){return server;}
    public boolean feature(Feature feature) {
        if(feature!=Feature.BLOCK_RENDERING && feature!=Feature.ENTITY_RENDERING && feature!=Feature.CELERITAS)
            throw new IllegalArgumentException("Only rendering preferences are combined");
        return server.hasRgbLane() && server.feature(feature) && local.feature(feature);
    }
    public boolean allowed(RuleKind kind,String id,Map<String,String> state) {
        Feature feature=switch(kind){case BLOCK_RENDERING->Feature.BLOCK_RENDERING;case ENTITY_RENDERING->Feature.ENTITY_RENDERING;default->throw new IllegalArgumentException("Not a rendering rule");};
        if(!feature(feature) || id==null || !server.resolve(kind,id,state,null).enabled())return false;
        // Local simulation lane/filter/source preferences cannot redefine remote simulation.
        for(var rule:local.rules(kind))if(rule.mode()==RuleMode.OFF && rule.target().matches(id,state))return false;
        return true;
    }
}

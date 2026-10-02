package com.sumirelabs.pcla.config;

import com.sumirelabs.pcla.light.*;
import java.util.*;

/** Registry-only diagnostics. No contextual world, light or TileEntity callbacks. */
public final class PaletteAudit {
    public record Source(String id,Rgb ratio,int registeredStates,int enabledStates,
                         Set<Integer> resolvedRgbAt15,Map<String,Set<String>> properties) {}
    public record Rule(RuleKind kind,String selector,RuleMode mode,String unit,int matches,int enabledMatches,String status) {}
    public record Report(String scope,String simulationProfile,List<Source> sources,List<Rule> rules) {}
    public static <S> Report inspect(LightingConfig config,List<CompiledColorTable.State<S>> states,Set<String> entities) {
        var sources=new ArrayList<Source>();var rules=new ArrayList<Rule>();
        for(var entry:ColorDefaults.sources().entrySet()) {
            int count=0,enabled=0;var colors=new TreeSet<Integer>();var properties=new TreeMap<String,Set<String>>();
            for(var state:states)if(entry.getKey().equals(state.registryId())) {
                count++;state.properties().forEach((name,value)->properties.computeIfAbsent(name,k->new TreeSet<>()).add(value));
                var decision=config.resolve(RuleKind.EMISSION,state.registryId(),state.properties(),entry.getValue());
                if(decision.enabled())enabled++;
                colors.add((decision.enabled()?decision.rgb():Rgb.WHITE).atIntensity(15).packed());
            }
            sources.add(new Source(entry.getKey(),entry.getValue(),count,enabled,colors,properties));
        }
        for(var kind:RuleKind.values())for(var rule:config.rules(kind)) {
            int matches=0,enabled=0;boolean idFound=false;
            var idSelector=new Selector(rule.target().id(),Map.of());
            if(kind==RuleKind.ENTITY_RENDERING) {
                for(var id:new TreeSet<>(entities))if(rule.target().matches(id,Map.of())) {
                    idFound=true;matches++;if(config.resolve(kind,id,Map.of(),null).enabled())enabled++;
                }
            } else for(var state:states) {
                if(idSelector.matches(state.registryId(),Map.of()))idFound=true;
                if(rule.target().matches(state.registryId(),state.properties())) {
                    matches++;if(config.resolve(kind,state.registryId(),state.properties(),null).enabled())enabled++;
                }
            }
            rules.add(new Rule(kind,rule.target().canonical(),rule.mode(),kind==RuleKind.ENTITY_RENDERING?"entity_ids":"block_states",
                    matches,enabled,matches>0?"matched":idFound?"no_matching_state":"target_not_registered"));
        }
        return new Report("server startup settings; client display vetoes are additional",config.simulationProfile(),List.copyOf(sources),List.copyOf(rules));
    }
    private PaletteAudit(){}
}

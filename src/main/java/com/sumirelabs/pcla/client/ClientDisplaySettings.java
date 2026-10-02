package com.sumirelabs.pcla.client;

import com.sumirelabs.pcla.config.*;
import com.sumirelabs.pcla.light.MinecraftColorTable;
import com.sumirelabs.pcla.network.*;
import net.minecraft.block.state.IBlockState;
import java.util.*;

/** Client-only, immutable compiled decisions bound to one lighting session; never changes server globals. */
public final class ClientDisplaySettings {
    public record State(UUID session,long epoch,int dimension,boolean synchronizedSettings,ClientRenderPolicy policy,Set<IBlockState> blocks) {}
    private static LightingConfig local;
    private static volatile State active;
    private static final Map<String,Boolean> entities=new HashMap<>(); // Client render thread only.
    public static void configure(LightingConfig config){local=Objects.requireNonNull(config);}
    public static LightingConfig local(){return local;}
    public static State active(){return active;}
    public static boolean matches(ClientRegionStore.Snapshot snapshot,State state){return snapshot!=null && state!=null && state.session().equals(snapshot.session()) && state.epoch()==snapshot.epoch() && state.dimension()==snapshot.dimension();}
    public static void install(RegionFrame hello,ServerSettings remote) {
        var old=active;if(old!=null && old.session().equals(hello.session) && old.epoch()==hello.epoch)return;
        var policy=new ClientRenderPolicy(local,remote==null?local:remote.config());
        var blocks=MinecraftColorTable.compileRenderRules(policy);
        entities.clear();active=new State(hello.session,hello.epoch,hello.dimension,remote!=null,policy,blocks);
        org.apache.logging.log4j.LogManager.getLogger("PCLA").info("RGB settings installed synchronized={} session={} epoch={} dimension={} serverProfile={} localProfile={}",remote!=null,hello.session,hello.epoch,hello.dimension,policy.simulation().simulationProfile(),local.simulationProfile());
    }
    public static boolean blockAllowed(ClientRegionStore.Snapshot snapshot,IBlockState state){var settings=active;return matches(snapshot,settings) && settings.blocks().contains(MinecraftColorTable.clean(state));}
    public static boolean entityAllowed(ClientRegionStore.Snapshot snapshot,String id){var settings=active;return matches(snapshot,settings) && id!=null && entities.computeIfAbsent(id,key->settings.policy().allowed(RuleKind.ENTITY_RENDERING,key,Map.of()));}
    public static boolean celeritasAllowed(ClientRegionStore.Snapshot snapshot){var settings=active;return matches(snapshot,settings) && settings.policy().feature(Feature.CELERITAS);}
    public static void clear() {
        if(active!=null)org.apache.logging.log4j.LogManager.getLogger("PCLA").info("RGB settings cleared session={}",active.session());
        active=null;entities.clear();
    }
    private ClientDisplaySettings() {}
}

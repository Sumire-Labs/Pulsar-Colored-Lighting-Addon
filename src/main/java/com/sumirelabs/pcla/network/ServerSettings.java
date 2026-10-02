package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.config.LightingConfig;
import java.util.*;

/** Immutable settings tied to the same world stream as its lighting hello. */
public record ServerSettings(UUID session,long epoch,int dimension,int protocol,String profile,LightingConfig config) {
    public ServerSettings {
        Objects.requireNonNull(session);Objects.requireNonNull(config);
        if(epoch<=0 || protocol<1 || protocol>4 || !config.simulationProfile().equals(profile))throw new IllegalArgumentException("Incompatible RGB settings profile");
    }
    public boolean matches(RegionFrame hello){return hello.kind==RegionFrame.Kind.HELLO && session.equals(hello.session) && epoch==hello.epoch && dimension==hello.dimension && protocol==hello.protocol && profile.equals(hello.profile);}
    public RegionFrame hello(){return switch(protocol){case 4->RegionFrame.helloV4(session,epoch,dimension,profile);case 3->RegionFrame.helloV3(session,epoch,dimension,profile);case 2->RegionFrame.helloV2(session,epoch,dimension,profile);default->RegionFrame.hello(session,epoch,dimension,profile);};}
}

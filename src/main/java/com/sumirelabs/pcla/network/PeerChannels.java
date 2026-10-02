package com.sumirelabs.pcla.network;

import java.util.*;

/** Remote channel registration is a capability signal scoped to one physical connection. */
public final class PeerChannels<K> {
    public static final String V2="pcla_rgb_v2";
    public static final String V3="pcla_rgb_v3";
    public static final String V4="pcla_rgb_v4";
    public static final String SETTINGS="pcla_cfg_v1";
    private final Map<K,Set<String>> capable=new WeakHashMap<>();
    public synchronized void registration(K connection,String operation,Set<String> channels) {
        Objects.requireNonNull(connection);
        for(String channel:List.of(V2,V3,V4,SETTINGS))if(channels.contains(channel)) {
            if(operation.equals("REGISTER"))capable.computeIfAbsent(connection,k->new HashSet<>()).add(channel);
            else if(operation.equals("UNREGISTER")){var present=capable.get(connection);if(present!=null){present.remove(channel);if(present.isEmpty())capable.remove(connection);}}
        }
    }
    public synchronized boolean supportsV2(K connection) { return capable.getOrDefault(connection,Set.of()).contains(V2); }
    public synchronized boolean supportsV3(K connection) { return capable.getOrDefault(connection,Set.of()).contains(V3); }
    public synchronized boolean supportsV4(K connection) { return capable.getOrDefault(connection,Set.of()).contains(V4); }
    public synchronized boolean supportsSettings(K connection) { return capable.getOrDefault(connection,Set.of()).contains(SETTINGS); }
    public synchronized void disconnected(K connection) { capable.remove(connection); }
}

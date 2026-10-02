package com.sumirelabs.pcla.light.world;

import java.util.*;

/** Chunk-keyed calculation owners. Subscription is explicit until automatic interest is connected. */
public final class ChunkDomainDirectory<C,R> {
    public record Entry<C,R>(long chunkKey,CapturedRgbRegion.Bounds bounds,RegionPublication<C,R> gate) implements LightDomain<C,R> {}
    private final Map<Long,C> loaded=new HashMap<>();
    private final Map<Long,Entry<C,R>> entries=new LinkedHashMap<>();
    private final Map<Long,Set<Entry<C,R>>> dependencies=new HashMap<>();
    private boolean closed;

    public synchronized Entry<C,R> watch(int x,int z,CapturedRgbRegion.Bounds bounds) {
        if(closed) throw new IllegalStateException("World closed");
        if(bounds.x()!=Math.multiplyExact(x,16) || bounds.z()!=Math.multiplyExact(z,16) || bounds.sectionsX()!=1 || bounds.sectionsZ()!=1)
            throw new IllegalArgumentException("Chunk domain must own one horizontal chunk");
        long key=RegionPublication.key(x,z); var old=entries.get(key);
        if(old!=null && old.bounds.equals(bounds)) return old;
        remove(x,z);
        var gate=new RegionPublication<C,R>(); var entry=new Entry<C,R>(key,bounds,gate);
        gate.watch(bounds.chunks());
        for(long dependency:bounds.chunks()) {
            C chunk=loaded.get(dependency); if(chunk!=null) gate.loaded(dependency,chunk);
            dependencies.computeIfAbsent(dependency,k->new LinkedHashSet<>()).add(entry);
        }
        entries.put(key,entry); return entry;
    }
    public synchronized boolean remove(int x,int z) {
        var old=entries.remove(RegionPublication.key(x,z)); if(old==null) return false;
        old.gate.close();
        for(long dependency:old.bounds.chunks()) {
            var owners=dependencies.get(dependency); owners.remove(old); if(owners.isEmpty()) dependencies.remove(dependency);
        }
        return true;
    }
    public synchronized void loaded(long key,C chunk) {
        if(closed) return;
        loaded.put(key,Objects.requireNonNull(chunk)); for(var entry:affected(key)) entry.gate.loaded(key,chunk);
    }
    public synchronized void unloaded(long key) {
        loaded.remove(key); for(var entry:affected(key)) entry.gate.unloaded(key);
    }
    public synchronized void changed(long key) { for(var entry:affected(key)) entry.gate.changed(key); }
    public synchronized void changedAt(int x,int y,int z) {
        long key=RegionPublication.key(x>>4,z>>4);
        for(var entry:affected(key)) if(entry.bounds.containsInput(x,y,z)) entry.gate.changedAt(key,entry.bounds.inputCell(x,y,z));
    }
    public synchronized boolean containsInput(int x,int y,int z) {
        for(var entry:affected(RegionPublication.key(x>>4,z>>4))) if(entry.bounds.containsInput(x,y,z)) return true;
        return false;
    }
    public synchronized Entry<C,R> get(int x,int z) { return entries.get(RegionPublication.key(x,z)); }
    public synchronized C loadedChunk(long key) {return loaded.get(key);}
    public synchronized List<Entry<C,R>> entries() { return List.copyOf(entries.values()); }
    public synchronized List<Entry<C,R>> affected(long key) {
        var owners=dependencies.get(key); return owners==null?List.of():List.copyOf(owners);
    }
    public synchronized void close() {
        closed=true; for(var entry:entries.values()) entry.gate.close(); entries.clear(); dependencies.clear(); loaded.clear();
    }
}

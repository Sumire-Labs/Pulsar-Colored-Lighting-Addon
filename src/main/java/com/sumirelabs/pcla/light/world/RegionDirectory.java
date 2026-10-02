package com.sumirelabs.pcla.light.world;

import java.util.*;

/** Bounded subscriptions with chunk-indexed invalidation and a separate lifecycle gate per region. */
public final class RegionDirectory<C, R> {
    public static final int MAX_REGIONS = 4;
    public record Entry<C, R>(int slot, CapturedRgbRegion.Bounds bounds, RegionPublication<C, R> gate) implements LightDomain<C,R> {}
    private final Map<Long, C> loaded = new HashMap<>();
    private final Map<Integer, Entry<C, R>> regions = new TreeMap<>();
    private final Map<Long, Set<Entry<C, R>>> byChunk = new HashMap<>();
    private boolean closed;
    private final boolean includeSkyColumns;
    public RegionDirectory() { this(false); }
    public RegionDirectory(boolean includeSkyColumns) { this.includeSkyColumns=includeSkyColumns; }

    public synchronized Entry<C, R> replace(int slot, CapturedRgbRegion.Bounds bounds) {
        if (closed) throw new IllegalStateException("World closed");
        if (slot < 0 || slot >= MAX_REGIONS) throw new IllegalArgumentException("Invalid region slot");
        Objects.requireNonNull(bounds);
        if(bounds.sectionsY()>2) throw new IllegalArgumentException("Legacy region slots allow at most two vertical sections");
        remove(slot);
        var gate = new RegionPublication<C, R>();
        var entry = new Entry<>(slot, bounds, gate);
        var dependencies = bounds.chunks();
        gate.watch(dependencies);
        for (long key : dependencies) {
            C chunk = loaded.get(key);
            if (chunk != null) gate.loaded(key, chunk);
            byChunk.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(entry);
        }
        regions.put(slot, entry);
        return entry;
    }

    public synchronized int add(CapturedRgbRegion.Bounds bounds) {
        if (closed) throw new IllegalStateException("World closed");
        // Slot zero remains the primary diagnostic region.
        for (int slot = 1; slot < MAX_REGIONS; slot++) if (!regions.containsKey(slot)) {
            replace(slot, bounds); return slot;
        }
        throw new IllegalStateException("All auxiliary RGB region slots are in use");
    }

    public synchronized boolean remove(int slot) {
        var old = regions.remove(slot);
        if (old == null) return false;
        old.gate.close(); // Retired workers can never publish into a reused slot.
        for (long key : old.bounds.chunks()) {
            var entries = byChunk.get(key);
            entries.remove(old);
            if (entries.isEmpty()) byChunk.remove(key);
        }
        return true;
    }

    public synchronized void loaded(long key, C chunk) {
        if (closed) return;
        loaded.put(key, Objects.requireNonNull(chunk));
        for (var region : affected(key)) region.gate.loaded(key, chunk);
    }
    public synchronized void unloaded(long key) {
        loaded.remove(key);
        for (var region : affected(key)) region.gate.unloaded(key);
    }
    public synchronized void changed(long key) {
        for (var region : affected(key)) region.gate.changed(key);
    }
    public synchronized void changedAt(int x, int y, int z) {
        long key = RegionPublication.key(x >> 4, z >> 4);
        for (var region : affected(key)) {
            var bounds=region.bounds;
            int cell=bounds.containsInput(x,y,z) ? bounds.inputCell(x,y,z) : -1;
            int column=includeSkyColumns && y>=bounds.inputTopY() && bounds.containsColumn(x,z) ? bounds.inputColumn(x,z) : -1;
            if(cell>=0 || column>=0) region.gate.changedInputs(key,cell,column);
        }
    }
    public synchronized boolean containsInput(int x, int y, int z) {
        for (var region : affected(RegionPublication.key(x >> 4,z >> 4)))
            if (region.bounds.containsInput(x,y,z) || includeSkyColumns && y>=region.bounds.inputTopY() && region.bounds.containsColumn(x,z)) return true;
        return false;
    }
    public synchronized Entry<C, R> get(int slot) { return regions.get(slot); }
    public synchronized List<Entry<C, R>> entries() { return List.copyOf(regions.values()); }
    public synchronized List<Entry<C, R>> affected(long key) {
        var entries = byChunk.get(key);
        return entries == null ? List.of() : List.copyOf(entries);
    }
    public synchronized void close() {
        closed = true;
        for (var region : regions.values()) region.gate.close();
        regions.clear(); byChunk.clear(); loaded.clear();
    }
}

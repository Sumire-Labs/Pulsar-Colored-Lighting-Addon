package com.sumirelabs.pcla.light.world;

import java.util.*;

/** Calculation owner's lifecycle gate. All mutation and publication decisions share one lock. */
public final class RegionPublication<C, S> {
    public static final class Ticket<C> {
        private final long revision;
        private final Map<Long,C> chunks;
        private final boolean fullCapture;
        private final DirtyCells.Snapshot dirty;
        private final BitSet dirtySkyColumns;
        public Ticket(long revision,Map<Long,C> chunks,boolean fullCapture,BitSet dirtyCells) { this(revision,chunks,fullCapture,dirtyCells,new BitSet()); }
        public Ticket(long revision,Map<Long,C> chunks,boolean fullCapture,BitSet dirtyCells,BitSet dirtySkyColumns) {
            this(revision,chunks,fullCapture,DirtyCells.Snapshot.from(dirtyCells),dirtySkyColumns);
        }
        private Ticket(long revision,Map<Long,C> chunks,boolean fullCapture,DirtyCells.Snapshot dirty,BitSet dirtySkyColumns) {
            this.revision=revision;this.chunks=Map.copyOf(chunks);this.fullCapture=fullCapture;
            this.dirty=dirty;this.dirtySkyColumns=(BitSet)dirtySkyColumns.clone();
        }
        public long revision() { return revision; }
        public Map<Long,C> chunks() { return chunks; }
        public boolean fullCapture() { return fullCapture; }
        /** Compatibility copy; workers should visit immutable dirty coordinates directly. */
        public BitSet dirtyCells() { return dirty.copy(); }
        public BitSet dirtySkyColumns() { return (BitSet)dirtySkyColumns.clone(); }
        public void forEachDirty(java.util.function.IntConsumer visitor) { dirty.forEach(visitor); }
        public int maximumDirtyCell() { return dirty.maximum(); }
        @Override public boolean equals(Object other) {
            return other instanceof Ticket<?> t && revision==t.revision && fullCapture==t.fullCapture
                    && chunks.equals(t.chunks) && dirtySkyColumns.equals(t.dirtySkyColumns) && dirtyCells().equals(t.dirtyCells());
        }
        @Override public int hashCode() { return Objects.hash(revision,chunks,fullCapture,dirtyCells(),dirtySkyColumns); }
        @Override public String toString() { return "Ticket[revision="+revision+", chunks="+chunks+", fullCapture="+fullCapture+", dirtyCells="+dirtyCells()+", dirtySkyColumns="+dirtySkyColumns+"]"; }
    }
    private final Map<Long, C> loaded = new HashMap<>();
    private Set<Long> dependencies = Set.of();
    private long revision;
    private boolean closed;
    private S visible;
    private S display;
    private boolean fullCapture;
    private final DirtyCells dirtyCells = new DirtyCells();
    private final BitSet dirtySkyColumns=new BitSet();

    public synchronized void watch(Set<Long> chunks) {
        if (closed) throw new IllegalStateException("World closed");
        if (chunks.isEmpty()) throw new IllegalArgumentException("Empty region");
        dependencies = Set.copyOf(chunks);
        invalidate();
    }

    public synchronized void loaded(long key, C chunk) {
        if (closed) return;
        C old = loaded.put(key, Objects.requireNonNull(chunk));
        if (old != chunk && dependencies.contains(key)) invalidate();
    }

    public synchronized void unloaded(long key) {
        loaded.remove(key);
        if (dependencies.contains(key)) invalidate();
    }

    public synchronized void changed(long key) {
        if (!closed && dependencies.contains(key)) invalidate(false);
    }

    public synchronized boolean contains(long key) { return !closed && dependencies.contains(key); }

    public synchronized void changedAt(long key, int cell) {
        if (cell < 0) throw new IllegalArgumentException("Negative input cell");
        changedInputs(key,cell,-1);
    }

    /** One notification can alter both a captured cell and its sky boundary atomically. */
    public synchronized void changedInputs(long key,int cell,int skyColumn) {
        if(cell< -1 || skyColumn< -1 || cell<0 && skyColumn<0) throw new IllegalArgumentException("Invalid changed input");
        if (!closed && dependencies.contains(key)) {
            if(cell>=0) dirtyCells.add(cell);
            if(skyColumn>=0) dirtySkyColumns.set(skyColumn);
            revision++;
            visible = null;
        }
    }

    public synchronized Ticket<C> begin() {
        if (closed || dependencies.isEmpty()) return null;
        Map<Long, C> inputs = new HashMap<>();
        for (long key : dependencies) {
            C chunk = loaded.get(key);
            if (chunk == null) return null;
            inputs.put(key, chunk);
        }
        return new Ticket<>(revision, inputs, fullCapture, dirtyCells.snapshot(),dirtySkyColumns);
    }

    public synchronized boolean publish(Ticket<C> ticket, S snapshot) {
        Objects.requireNonNull(ticket); Objects.requireNonNull(snapshot);
        if (ticket.revision != revision || !ownsChunks(ticket)) return false;
        visible = snapshot;
        display = snapshot;
        fullCapture = false;
        dirtyCells.clear();
        dirtySkyColumns.clear();
        return true;
    }

    /** Identity/lifetime check for a paused job. New edits still reject its eventual publication. */
    public synchronized boolean ownsChunks(Ticket<C> ticket) {
        Objects.requireNonNull(ticket);
        if(closed || !ticket.chunks.keySet().equals(dependencies))return false;
        for(var entry:ticket.chunks.entrySet())if(loaded.get(entry.getKey())!=entry.getValue())return false;
        return true;
    }

    public synchronized S snapshot() { return visible; }
    /** Last completed display only; pending edits still make snapshot() unavailable. */
    public synchronized S displaySnapshot() { return display; }
    public synchronized void failed() { invalidate(); }
    public synchronized long revision() { return revision; }
    public synchronized void close() { closed = true; loaded.clear(); dependencies = Set.of(); invalidate(); }
    private void invalidate() { invalidate(true); }
    private void invalidate(boolean retireDisplay) {
        revision++; visible = null; fullCapture = true; dirtyCells.clear(); dirtySkyColumns.clear();
        if(retireDisplay) display=null;
    }
    public static long key(int x, int z) { return (long)x & 0xffffffffL | (long)z << 32; }
}

package com.sumirelabs.pcla.light.engine;

import java.util.BitSet;
import java.util.Objects;

/**
 * Single-writer RGB block propagation core. Invalidates dependent components
 * before increasing from surviving light and current emitters. No skylight:
 * zero-loss downward sky edges invalidate the strict-decrease assumption.
 *
 * The core is independent of Minecraft storage and never publishes light.
 * If access throws, the caller must discard/rebuild the working result; a
 * partially completed operation must not be marked ready or published.
 */
public final class RgbWordBlockPropagator {
    public record Work(long decreaseVisits, long increaseVisits, long writes, long absorptionReads) {}
    private final RgbWordBlockAccess access;
    // Decrease entries reserve the low 24 bits for sparse words; cell IDs start at bit 24.
    private final LongWorkQueue decreases = new LongWorkQueue();
    private final LongWorkQueue increases = new LongWorkQueue();
    private final BitSet increasePending = new BitSet();
    private long decreaseVisits, increaseVisits, writes, absorptionReads;

    public RgbWordBlockPropagator(RgbWordBlockAccess access) { this.access = Objects.requireNonNull(access); }

    /** Work phases retained between bounded advances. */
    private enum Phase { IDLE, RESET, ROOTS, DECREASE, INCREASE }
    private Phase phase=Phase.IDLE;
    private int cursor;
    private int[] roots;
    private Work completed;
    private boolean requiresRelight;

    public Work relight() {startRelight();while(!advance(16384)){}return completedWork();}
    public Work update(int... changedCells) {startUpdate(changedCells);while(!advance(16384)){}return completedWork();}

    /** Inputs must remain stable until completion. No snapshot is published by the core. */
    public void startRelight() {
        requireIdle();begin();cursor=0;roots=null;completed=null;phase=Phase.RESET;
    }
    /** Validates and copies all roots before modifying light; later caller-array edits are harmless. */
    public void startUpdate(int... changedCells) {
        requireIdle();
        if(requiresRelight)throw new IllegalStateException("Cancelled or failed propagation requires a rebuild");
        Objects.requireNonNull(changedCells);
        for(int cell:changedCells)if(cell<0 || cell>=access.cellCount())throw new IndexOutOfBoundsException("Cell "+cell);
        int[] copy=changedCells.clone();begin();roots=copy;cursor=0;completed=null;phase=Phase.ROOTS;
    }
    public boolean isRunning() {return phase!=Phase.IDLE;}
    public Work completedWork() {
        if(completed==null)throw new IllegalStateException("Propagation has not completed");
        return completed;
    }
    /** One operation scans a cell/root or pops one queue entry (including all six edges). */
    public boolean advance(int maximumOperations) {
        if(maximumOperations<1)throw new IllegalArgumentException("Positive propagation budget required");
        try {
            for(int operation=0;operation<maximumOperations;operation++) {
                settle();
                if(phase==Phase.IDLE)return true;
                switch(phase) {
                    case RESET -> {
                        int cell=cursor++;write(cell,0);
                        if(access.emission(cell)!=0)seed(cell);
                    }
                    case ROOTS -> {
                        int cell=roots[cursor++],old=access.light(cell);
                        if(old!=0){write(cell,old,0);decreases.add((long)cell<<24|old);}
                        seed(cell);seedNeighbors(cell);
                    }
                    case DECREASE -> decreaseOne();
                    case INCREASE -> increaseOne();
                    default -> throw new AssertionError(phase);
                }
            }
            settle();return phase==Phase.IDLE;
        } catch(RuntimeException|Error failure) {cancel();throw failure;}
    }
    /** Discards queues, not partially changed light. The caller must rebuild or discard its access. */
    public void cancel() {clearQueues();phase=Phase.IDLE;roots=null;completed=null;requiresRelight=true;}
    private void requireIdle() {if(isRunning())throw new IllegalStateException("Propagation already running");}
    private void settle() {
        if(phase==Phase.RESET && cursor==access.cellCount())phase=Phase.INCREASE;
        if(phase==Phase.ROOTS && cursor==roots.length){roots=null;phase=Phase.DECREASE;}
        if(phase==Phase.DECREASE && decreases.isEmpty())phase=Phase.INCREASE;
        if(phase==Phase.INCREASE && increases.isEmpty()) {
            completed=work();clearQueues();phase=Phase.IDLE;requiresRelight=false;
        }
    }
    private void decreaseOne() {
        long entry=decreases.remove();int cell=(int)(entry>>>24),removed=(int)entry&PackedWordRgb.MASK;
        decreaseVisits++;
        for(int direction=0;direction<6;direction++) {
            int neighbor=access.neighbor(cell,direction);if(neighbor<0)continue;
            int current=access.light(neighbor);
            // A previously consistent emitter is either still lit, already
            // seeded when first cleared, or seeded as a changed root.
            if(current==0)continue;
            int dependent=PackedWordRgb.dependentComponents(current,removed);
            if(dependent!=0){write(neighbor,current,current^dependent);decreases.add((long)neighbor<<24|dependent);}
            // Cleared, non-emitting cells cannot start an increase. Any surviving
            // neighbor that later lights them will enqueue them at that point.
            if((current^dependent)!=0 || access.emission(neighbor)!=0)seed(neighbor);
        }
    }
    private void increaseOne() {
        int cell=(int)increases.remove();increasePending.clear(cell);increaseVisits++;
        int old=access.light(cell);
        int light=PackedWordRgb.union(old,access.emission(cell));write(cell,old,light);
        int maximumCandidate=PackedWordRgb.attenuateBlock(light,0);
        if(maximumCandidate==0)return;
        for(int direction=0;direction<6;direction++) {
            int neighbor=access.neighbor(cell,direction);if(neighbor<0)continue;
            int current=access.light(neighbor);

            if(!PackedWordRgb.anyGreater(maximumCandidate,current))continue;
            absorptionReads++;
            int candidate=PackedWordRgb.attenuateBlock(light,access.absorption(cell,direction));
            int next=PackedWordRgb.union(current,candidate);
            if(next!=current){write(neighbor,current,next);seed(neighbor);}
        }
    }

    private void seedNeighbors(int cell) {
        for (int direction = 0; direction < 6; direction++) {
            int neighbor = access.neighbor(cell, direction);
            if (neighbor >= 0) seed(neighbor);
        }
    }

    private void seed(int cell) {
        if (!increasePending.get(cell)) {
            increases.add(cell);
            increasePending.set(cell);
        }
    }

    private void write(int cell, int light) {
        write(cell,access.light(cell),light);
    }
    /** The worker is the only light writer, so a value already read in this step remains valid. */
    private void write(int cell,int old,int light) {
        if (old != light) { access.light(cell, light); writes++; }
    }

    private Work work() { return new Work(decreaseVisits, increaseVisits, writes, absorptionReads); }
    private void begin() { clearQueues(); decreaseVisits = increaseVisits = writes = absorptionReads = 0; }
    private void clearQueues() { decreases.clear(); increases.clear(); increasePending.clear(); }
}

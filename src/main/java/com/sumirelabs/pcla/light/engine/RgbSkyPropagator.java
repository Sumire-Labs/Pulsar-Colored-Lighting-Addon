package com.sumirelabs.pcla.light.engine;

import com.sumirelabs.pcla.light.PackedRgb;
import java.util.BitSet;
import java.util.Objects;

/**
 * Detached single-writer sky core. Only full-strength components on downward
 * edges may have zero loss. Decreases therefore also follow equal 15 values
 * downward; all other dependent components strictly decrease.
 * No world capture, sky-boundary discovery, networking or publication is done
 * here. If access fails, the caller must discard/rebuild the partial result.
 */
public final class RgbSkyPropagator {
    public record Work(long decreaseVisits,long increaseVisits,long writes,long absorptionReads) {}
    private final RgbSkyAccess access;
    private final LongWorkQueue decreases=new LongWorkQueue(),increases=new LongWorkQueue();
    private final BitSet increasePending=new BitSet();
    private long decreaseVisits,increaseVisits,writes,absorptionReads;
    public RgbSkyPropagator(RgbSkyAccess access) { this.access=Objects.requireNonNull(access); }

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
                        if(access.boundaryLight(cell)!=0)seed(cell);
                    }
                    case ROOTS -> {
                        int cell=roots[cursor++],old=access.light(cell);
                        if(old!=0){write(cell,old,0);decreases.add((long)cell<<12|old);}
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
        long entry=decreases.remove();int cell=(int)(entry>>>12),removed=(int)entry&0xfff;
        decreaseVisits++;
        for(int direction=0;direction<6;direction++) {
            int neighbor=access.neighbor(cell,direction);if(neighbor<0)continue;
            int current=access.light(neighbor);
            int dependent=RgbSkyRules.dependentComponents(current,removed,direction==3);
            if(dependent!=0){write(neighbor,current,current^dependent);decreases.add((long)neighbor<<12|dependent);}
            // A boundary source must be reseeded even if decrease just cleared its
            // working value; other dark cells wait for a real incoming increase.
            if((current^dependent)!=0 || access.boundaryLight(neighbor)!=0)seed(neighbor);
        }
    }
    private void increaseOne() {
        int cell=(int)increases.remove();increasePending.clear(cell);increaseVisits++;
        int old=access.light(cell);
        int light=PackedRgb.union(old,access.boundaryLight(cell));write(cell,old,light);
        if(light==0)return;
        for(int direction=0;direction<6;direction++) {
            int neighbor=access.neighbor(cell,direction);if(neighbor<0)continue;
            int current=access.light(neighbor);
            int maximumCandidate=RgbSkyRules.attenuate(light,0,direction==3);
            if(PackedRgb.union(current,maximumCandidate)==current)continue;
            absorptionReads++;
            int candidate=RgbSkyRules.attenuate(light,access.absorption(cell,direction),direction==3);
            int next=PackedRgb.union(current,candidate);
            if(next!=current){write(neighbor,current,next);seed(neighbor);}
        }
    }

    private void seedNeighbors(int cell) { for(int direction=0;direction<6;direction++) { int n=access.neighbor(cell,direction); if(n>=0) seed(n); } }
    private void seed(int cell) { if(!increasePending.get(cell)) { increases.add(cell); increasePending.set(cell); } }
    private void write(int cell,int rgb) { write(cell,access.light(cell),rgb); }
    /** The worker is the only light writer, so a value already read in this step remains valid. */
    private void write(int cell,int old,int rgb) { if(old!=rgb) { access.light(cell,rgb); writes++; } }
    private Work work() { return new Work(decreaseVisits,increaseVisits,writes,absorptionReads); }
    private void begin() { clearQueues(); decreaseVisits=increaseVisits=writes=absorptionReads=0; }
    private void clearQueues() { decreases.clear(); increases.clear(); increasePending.clear(); }
}

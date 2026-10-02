package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.light.engine.RgbSkyBoundary;
import java.util.*;

/** Single-writer cache of known full-strength sky masks above a cropped field. */
public final class SkyColumnCache {
    public static final class Input { public int mask; public boolean contextual; }
    /** Fill both fields after checking column availability and directional absorption. */
    @FunctionalInterface public interface Reader { void read(int column,Input output); }
    public record Capture(int sampledColumns,int[] changedColumns) {}
    private final short[] masks;
    private final BitSet contextual=new BitSet();
    private boolean initialized;
    public SkyColumnCache(int columns) {
        if(columns<=0) throw new IllegalArgumentException("Empty sky column cache");
        masks=new short[columns];
    }
    public int columns() { return masks.length; }
    public int mask(int column) {
        if(!initialized) throw new IllegalStateException("Sky columns unavailable");
        return masks[column];
    }
    public Capture refresh(Reader reader,BitSet dirty,boolean full) {
        Objects.requireNonNull(reader); Objects.requireNonNull(dirty);
        if(dirty.length()>masks.length) throw new IndexOutOfBoundsException("Invalid sky column");
        var sampled=new BitSet(masks.length);
        if(full || !initialized) sampled.set(0,masks.length);
        else { sampled.or(dirty); sampled.or(contextual); }
        // An exception must not leave a mixture of old/new column masks readable.
        initialized=false;
        int[] changed=new int[sampled.cardinality()]; int changedCount=0;
        var input=new Input();
        for(int column=sampled.nextSetBit(0);column>=0;column=sampled.nextSetBit(column+1)) {
            input.mask=0; input.contextual=false; reader.read(column,input);
            RgbSkyBoundary.fullStrengthThrough(input.mask,0); // Validate 0-or-15 components.
            if(masks[column]!=input.mask) { masks[column]=(short)input.mask; changed[changedCount++]=column; }
            contextual.set(column,input.contextual);
        }
        initialized=true;
        return new Capture(sampled.cardinality(),Arrays.copyOf(changed,changedCount));
    }
}

package com.sumirelabs.pcla.light.world;

import java.util.BitSet;
import java.util.Objects;
import java.util.function.IntConsumer;

/** Gate-confined bitmap; sparse tickets do not copy up to their highest cell. */
final class DirtyCells {
    private final BitSet cells=new BitSet();
    void add(int cell) {
        if(cell<0) throw new IllegalArgumentException("Negative dirty cell");
        cells.set(cell);
    }
    Snapshot snapshot() { return Snapshot.from(cells); }
    void clear() { cells.clear(); }

    /** No mutable storage escapes; visitors retain ascending, unique coordinate order. */
    static final class Snapshot {
        private static final Snapshot EMPTY=new Snapshot(new int[0],null);
        private final int[] sparse;
        private final BitSet dense;
        private Snapshot(int[] sparse,BitSet dense) { this.sparse=sparse;this.dense=dense; }
        static Snapshot from(BitSet cells) {
            int count=cells.cardinality();
            if(count==0) return EMPTY;
            // Compare payload sizes: packed ints vs bitmap words. Accumulation stays
            // a bitmap, so dense update notifications do not pay hash-set costs.
            if(32L*count<=(long)cells.previousSetBit(Integer.MAX_VALUE)+1) {
                int[] sparse=new int[count];int index=0;
                for(int cell=cells.nextSetBit(0);cell>=0;) {
                    sparse[index++]=cell;
                    if(cell==Integer.MAX_VALUE) break;
                    cell=cells.nextSetBit(cell+1);
                }
                return new Snapshot(sparse,null);
            }
            return new Snapshot(null,(BitSet)cells.clone());
        }
        void forEach(IntConsumer visitor) {
            Objects.requireNonNull(visitor);
            if(sparse!=null) { for(int cell:sparse) visitor.accept(cell); }
            else for(int cell=dense.nextSetBit(0);cell>=0;) {
                visitor.accept(cell);
                if(cell==Integer.MAX_VALUE) break;
                cell=dense.nextSetBit(cell+1);
            }
        }
        int maximum() {
            if(sparse!=null) return sparse.length==0?-1:sparse[sparse.length-1];
            return dense.previousSetBit(Integer.MAX_VALUE);
        }
        BitSet copy() {
            if(dense!=null) return (BitSet)dense.clone();
            BitSet cells=new BitSet(); for(int cell:sparse) cells.set(cell); return cells;
        }
    }
}

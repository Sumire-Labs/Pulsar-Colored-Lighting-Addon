package com.sumirelabs.pcla.light.engine;

import com.sumirelabs.pcla.light.PackedRgb;

/** Full-strength direct-sky mask for a known column above a bounded capture. */
public final class RgbSkyBoundary {
    private RgbSkyBoundary() {}
    /**
     * Each mask component is 0 or 15. Any positive absorption/face closure
     * prevents that component remaining 15 below the edge. This is NOT the
     * actual sky value: weakened components are deliberately omitted. It may
     * seed a cropped solve only when every published cell has a 15-edge margin
     * from that top boundary, so omitted <=14 light cannot reach the output.
     */
    public static int fullStrengthThrough(int mask,int absorption) {
        PackedRgb.validate(mask); PackedRgb.validate(absorption);
        int result=0;
        for(int shift=0;shift<=8;shift+=4) {
            int value=mask>>shift&15;
            if(value!=0 && value!=15) throw new IllegalArgumentException("Expected a full-strength sky mask");
            if((absorption>>shift&15)==0) result|=value<<shift;
        }
        return result;
    }
}

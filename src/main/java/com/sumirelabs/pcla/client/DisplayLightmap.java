package com.sumirelabs.pcla.client;

/** Immutable copy of the actual 16x16 scalar lightmap, used as a display curve only. */
public final class DisplayLightmap {
    private final int[] argb;
    public DisplayLightmap(int[] argb) {
        if(argb.length!=256)throw new IllegalArgumentException("Expected 16x16 lightmap");
        this.argb=argb.clone();
    }
    public float value(int block,int sky,int shift) {
        if(block<0 || block>15 || sky<0 || sky>15 || (shift!=0 && shift!=4 && shift!=8))
            throw new IllegalArgumentException("Invalid lightmap lookup");
        return (argb[sky*16+block]>>>(shift*2)&255)/255f;
    }
    public int[] copyColors(){return argb.clone();}
    public static float ratio(float numerator,float denominator) {
        // A black native channel cannot be recovered by a vertex multiplier.
        return denominator<=0?1:Math.max(0,Math.min(1,numerator/denominator));
    }
}

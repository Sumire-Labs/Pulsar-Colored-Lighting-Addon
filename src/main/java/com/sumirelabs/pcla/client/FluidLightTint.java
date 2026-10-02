package com.sumirelabs.pcla.client;

import com.sumirelabs.pcla.light.PackedRgb;

/** RGB counterpart of BlockLiquid's per-lane maximum at the face sample and the cell above it. */
public final class FluidLightTint {
    public static int merge(int here,int above) {
        if(here!=-1)PackedRgb.validate(here);if(above!=-1)PackedRgb.validate(above);
        // Incomplete coverage must retain the ordinary scalar lane, not silently assume darkness.
        return here<0 || above<0 ? -1 : PackedRgb.union(here,above);
    }
    public static void compute(int block,int blockAbove,int sky,int skyAbove,int sourceRgb,int emission,
                               int scalarLight,float daylight,float[] out) {
        compute(block,blockAbove,sky,skyAbove,sourceRgb,emission,scalarLight,daylight,out,null);
    }
    public static void compute(int block,int blockAbove,int sky,int skyAbove,int sourceRgb,int emission,
                               int scalarLight,float daylight,float[] out,DisplayLightmap map) {
        if(out.length!=3 || emission<0 || emission>15)throw new IllegalArgumentException("Invalid fluid tint input");
        if(sourceRgb!=-1)PackedRgb.validate(sourceRgb);
        int rgb=merge(block,blockAbove),skyRgb=merge(sky,skyAbove);
        int scalarBlock=scalarLight>>>4&15,scalarSky=scalarLight>>>20&15;
        if(emission>0){scalarBlock=Math.max(scalarBlock,emission);if(rgb>=0 && sourceRgb>=0)rgb=PackedRgb.union(rgb,sourceRgb);}
        for(int component=0;component<3;component++)out[component]=DisplayTint.channel(rgb,skyRgb,8-component*4,scalarBlock,scalarSky,daylight,map);
    }
    private FluidLightTint() {}
}

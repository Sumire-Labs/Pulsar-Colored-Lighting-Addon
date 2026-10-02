package com.sumirelabs.pcla.client;

import com.sumirelabs.pcla.light.PackedRgb;
import java.nio.ByteBuffer;

/** Prototype display approximation. Does not replace Minecraft's lightmap or RGB corner interpolation. */
public final class DisplayTint {
    static float brightness(int level) { return level/(60.0f-3.0f*level); }
    public static float channel(int rgb,int shift,int scalarBlock,int sky,float daylight) {
        return channel(rgb,-1,shift,scalarBlock,sky,daylight);
    }
    /** Missing lanes (-1) retain their ordinary scalar contribution. */
    public static float channel(int rgb,int skyRgb,int shift,int scalarBlock,int sky,float daylight) {
        return channel(rgb,skyRgb,shift,scalarBlock,sky,daylight,null);
    }
    public static float channel(int rgb,int skyRgb,int shift,int scalarBlock,int sky,float daylight,DisplayLightmap map) {
        if(rgb!=-1) PackedRgb.validate(rgb);
        if(skyRgb!=-1) PackedRgb.validate(skyRgb);
        if(scalarBlock<0 || scalarBlock>15 || sky<0 || sky>15 || !Float.isFinite(daylight) || daylight<0 || daylight>1)
            throw new IllegalArgumentException("Invalid display light");
        if(rgb<0) rgb=scalarBlock*0x111;
        if(skyRgb<0) skyRgb=sky*0x111;
        int peak=peak(rgb),skyPeak=peak(skyRgb);
        if(map!=null)return DisplayLightmap.ratio(map.value(rgb>>shift&15,skyRgb>>shift&15,shift),
                map.value(Math.max(peak,scalarBlock),Math.max(sky,skyPeak),shift));
        float numerator=0.03f+brightness(skyRgb>>shift&15)*daylight+brightness(rgb>>shift&15);
        float denominator=0.03f+brightness(Math.max(sky,skyPeak))*daylight+brightness(Math.max(peak,scalarBlock));
        return Math.min(1,numerator/denominator);
    }
    static int peak(int rgb) { return Math.max(rgb>>8,Math.max(rgb>>4&15,rgb&15)); }
    public static void multiply(ByteBuffer bytes,int offset,float r,float g,float b) {
        bytes.put(offset,(byte)Math.round((bytes.get(offset)&255)*r));
        bytes.put(offset+1,(byte)Math.round((bytes.get(offset+1)&255)*g));
        bytes.put(offset+2,(byte)Math.round((bytes.get(offset+2)&255)*b));
    }
    public static int multiplyAbgr(int color,float r,float g,float b) {
        return color&0xff000000 | Math.round((color&255)*r)
                | Math.round((color>>>8&255)*g)<<8 | Math.round((color>>>16&255)*b)<<16;
    }
}

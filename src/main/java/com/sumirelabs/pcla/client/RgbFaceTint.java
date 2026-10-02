package com.sumirelabs.pcla.client;

import com.sumirelabs.pcla.light.PackedRgb;
import java.util.Arrays;

/** Four face-corner display multipliers from a 3x3 light neighborhood. Not a replacement AO engine. */
public final class RgbFaceTint {
    public static final int OPAQUE=1<<20;
    public static int pack(int rgb,int block,int sky,boolean opaque) {
        PackedRgb.validate(rgb);
        if(block<0 || block>15 || sky<0 || sky>15) throw new IllegalArgumentException("Invalid scalar light");
        return rgb | block<<12 | sky<<16 | (opaque ? OPAQUE : 0);
    }
    /** Input index = (u+1) + 3*(v+1); corner output order = low/low, high/low, low/high, high/high. */
    public static void corners(int[] samples,float daylight,float[] out) {
        corners(samples,null,daylight,out);
    }
    public static void corners(int[] samples,int[] skySamples,float daylight,float[] out) {
        corners(samples,skySamples,daylight,out,null);
    }
    public static void corners(int[] samples,int[] skySamples,float daylight,float[] out,DisplayLightmap map) {
        if(samples.length!=9 || out.length!=12 || !Float.isFinite(daylight) || daylight<0 || daylight>1)
            throw new IllegalArgumentException("Invalid face samples");
        for(int sample:samples) if(sample!=-1 && (sample&~0x1fffff)!=0) throw new IllegalArgumentException("Invalid packed sample");
        if(skySamples!=null) {
            if(skySamples.length!=9) throw new IllegalArgumentException("Invalid sky samples");
            for(int rgb:skySamples) if(rgb!=-1) PackedRgb.validate(rgb);
        }
        int center=samples[4];
        if(center<0) { Arrays.fill(out,1); return; }
        for(int v=0;v<2;v++) for(int u=0;u<2;u++) {
            int sideU=3+u*2,sideV=1+v*6;
            int diagonal=(samples[sideU]>=0 && samples[sideV]>=0 && (samples[sideU]&OPAQUE)!=0 && (samples[sideV]&OPAQUE)!=0)
                    ? 4 : u*2+v*6;
            int a=usable(samples,sideU),b=usable(samples,sideV),c=usable(samples,diagonal);
            for(int component=0;component<3;component++) {
                int shift=8-component*4;
                float denominator=denominator(samples[4],sky(skySamples,4),daylight,map,shift)+denominator(samples[a],sky(skySamples,a),daylight,map,shift)
                        +denominator(samples[b],sky(skySamples,b),daylight,map,shift)+denominator(samples[c],sky(skySamples,c),daylight,map,shift);
                float value=numerator(samples[4],sky(skySamples,4),shift,daylight,map)+numerator(samples[a],sky(skySamples,a),shift,daylight,map)
                        +numerator(samples[b],sky(skySamples,b),shift,daylight,map)+numerator(samples[c],sky(skySamples,c),shift,daylight,map);
                out[(u+v*2)*3+component]=DisplayLightmap.ratio(value,denominator);
            }
        }
    }
    private static int usable(int[] samples,int index) { return samples[index]<0 || (samples[index]&OPAQUE)!=0 ? 4 : index; }
    /** Independent light levels for the dynamic GPU lightmap, before gamma or night vision. */
    public static void lightCorners(int[] samples,int[] skySamples,float[] blockOut,float[] skyOut) {
        if(samples.length!=9 || skySamples.length!=9 || blockOut.length!=12 || skyOut.length!=12 || samples[4]<0)
            throw new IllegalArgumentException("Complete center and four RGB corners required");
        for(int v=0;v<2;v++)for(int u=0;u<2;u++) {
            int sideU=3+u*2,sideV=1+v*6;
            int diagonal=samples[sideU]>=0 && samples[sideV]>=0 && (samples[sideU]&OPAQUE)!=0 && (samples[sideV]&OPAQUE)!=0?4:u*2+v*6;
            int a=usable(samples,sideU),b=usable(samples,sideV),c=usable(samples,diagonal);
            for(int component=0;component<3;component++) {
                int shift=8-component*4,index=(u+v*2)*3+component;
                blockOut[index]=((samples[4]>>shift&15)+(samples[a]>>shift&15)+(samples[b]>>shift&15)+(samples[c]>>shift&15))*0.25f;
                skyOut[index]=(skyLevel(samples,skySamples,4,shift)+skyLevel(samples,skySamples,a,shift)
                    +skyLevel(samples,skySamples,b,shift)+skyLevel(samples,skySamples,c,shift))*0.25f;
            }
        }
    }
    private static int skyLevel(int[] samples,int[] skies,int index,int shift){return skies[index]<0?samples[index]>>16&15:skies[index]>>shift&15;}
    private static int sky(int[] samples,int index) { return samples==null ? -1 : samples[index]; }
    private static float numerator(int sample,int skyRgb,int shift,float daylight,DisplayLightmap map) {
        int sky=skyRgb<0 ? sample>>16&15 : skyRgb>>shift&15;
        if(map!=null)return map.value(sample>>shift&15,sky,shift);
        return 0.03f+DisplayTint.brightness(sky)*daylight+DisplayTint.brightness(sample>>shift&15);
    }
    private static float denominator(int sample,int skyRgb,float daylight,DisplayLightmap map,int shift) {
        int peak=Math.max(sample>>8&15,Math.max(sample>>4&15,sample&15));
        int sky=sample>>16&15; if(skyRgb>=0) sky=Math.max(sky,DisplayTint.peak(skyRgb));
        if(map!=null)return map.value(Math.max(peak,sample>>12&15),sky,shift);
        return 0.03f+DisplayTint.brightness(sky)*daylight+DisplayTint.brightness(Math.max(peak,sample>>12&15));
    }
    public static float interpolate(float[] corners,int component,float u,float v) {
        if(component<0 || component>2 || corners.length!=12 || !Float.isFinite(u) || !Float.isFinite(v))
            throw new IllegalArgumentException("Invalid interpolation");
        u=Math.max(0,Math.min(1,u)); v=Math.max(0,Math.min(1,v));
        return (corners[component]*(1-u)+corners[3+component]*u)*(1-v)
                +(corners[6+component]*(1-u)+corners[9+component]*u)*v;
    }
}

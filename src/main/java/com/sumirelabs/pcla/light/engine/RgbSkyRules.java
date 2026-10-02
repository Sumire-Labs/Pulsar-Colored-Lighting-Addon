package com.sumirelabs.pcla.light.engine;

/** Per-component rules on validated 0xRGB values; never borrow across channels. */
public final class RgbSkyRules {
    private RgbSkyRules() {}
    public static int attenuate(int light,int absorption,boolean downward) {
        int result=0;
        for(int shift=0;shift<=8;shift+=4) {
            int level=light>>shift&15, opacity=absorption>>shift&15;
            int loss=downward && level==15 ? opacity : Math.max(1,opacity);
            result|=Math.max(0,level-loss)<<shift;
        }
        return result;
    }
    /** New opacity cannot prove independence: the previous edge may have been open. */
    public static int dependentComponents(int neighbor,int removed,boolean downward) {
        int result=0;
        for(int shift=0;shift<=8;shift+=4) {
            int value=neighbor>>shift&15, old=removed>>shift&15;
            if(value<old || downward && value==15 && old==15) result|=value<<shift;
        }
        return result;
    }
}

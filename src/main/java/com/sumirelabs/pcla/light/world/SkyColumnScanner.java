package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.light.engine.RgbSkyBoundary;

/** Full-strength vertical boundary scan; weak light is handled inside the captured region. */
public final class SkyColumnScanner {
    private SkyColumnScanner() {}
    @FunctionalInterface public interface Reader {
        void read(int y,CapturedRgbRegion.Input value);
        /** Lowest Y in a proven block-storage-free run, or y when no such proof is available. */
        default int emptyBottom(int y) { return y; }
    }
    public static long scan(int top,int bottom,int initialMask,Reader reader,CapturedRgbRegion.Input value,SkyColumnCache.Input out) {
        RgbSkyBoundary.fullStrengthThrough(initialMask,0);
        out.mask=initialMask; out.contextual=false;
        boolean aboveBlocked=false; long reads=0; int y=top;
        while(y>=bottom && out.mask!=0) {
            if(aboveBlocked) { out.mask=0; break; }
            reader.read(y,value); reads++;
            out.contextual|=value.contextual;
            out.mask=RgbSkyBoundary.fullStrengthThrough(out.mask,value.incoming[2]);
            aboveBlocked=(value.blockedFaces&(1<<3))!=0;
            int last=y;
            // Custom absorption or position-dependent air must retain ordinary per-block sampling.
            if(!value.contextual && value.incoming[2]==0 && !aboveBlocked) {
                int empty=reader.emptyBottom(y);
                if(empty>y) throw new IllegalArgumentException("Invalid empty sky run");
                last=Math.max(bottom,empty);
            }
            if(last==bottom) break; // Also avoids underflow at Integer.MIN_VALUE.
            y=last-1;
        }
        return reads;
    }
}

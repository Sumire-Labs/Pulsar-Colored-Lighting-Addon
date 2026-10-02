package com.sumirelabs.pcla.light.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SkyColumnScannerTest {
    private static void air(CapturedRgbRegion.Input value) {
        value.source=0; value.blockedFaces=0; value.contextual=false; Arrays.fill(value.incoming,0);
    }
    /** Original per-block algorithm, intentionally without the scanner's run-skipping code. */
    private static SkyColumnCache.Input reference(int top,int bottom,int mask,SkyColumnScanner.Reader reader) {
        var value=new CapturedRgbRegion.Input(); var out=new SkyColumnCache.Input(); out.mask=mask;
        boolean blocked=false;
        for(long y=top;y>=bottom && out.mask!=0;y--) {
            if(blocked) { out.mask=0; break; }
            reader.read((int)y,value); out.contextual|=value.contextual;
            for(int shift=0;shift<12;shift+=4) if((value.incoming[2]>>shift&15)>0) out.mask&=~(15<<shift);
            blocked=(value.blockedFaces&8)!=0;
        }
        return out;
    }
    @Test void emptyUpperSectionsNeedNineReadsInsteadOfOneHundredThirty() {
        var reader=new SkyColumnScanner.Reader() {
            public void read(int y,CapturedRgbRegion.Input out) { air(out); }
            public int emptyBottom(int y) { return y&~15; }
        };
        var out=new SkyColumnCache.Input();
        assertEquals(9,SkyColumnScanner.scan(255,126,0xfff,reader,new CapturedRgbRegion.Input(),out));
        assertEquals(0xfff,out.mask); assertFalse(out.contextual);
        assertEquals(reference(255,126,0xfff,reader).mask,out.mask);
    }
    @Test void customAirAbsorptionAndContextualAirCannotUseTheEmptyRunShortcut() {
        for(boolean contextual:new boolean[]{false,true}) {
            var reader=new SkyColumnScanner.Reader() {
                public void read(int y,CapturedRgbRegion.Input out) { air(out); out.contextual=contextual; out.incoming[2]=contextual?0:0x00f; }
                public int emptyBottom(int y) { fail("custom air must not skip"); return y; }
            };
            var out=new SkyColumnCache.Input();
            assertEquals(130,SkyColumnScanner.scan(255,126,0xfff,reader,new CapturedRgbRegion.Input(),out));
            assertEquals(contextual?0xfff:0xff0,out.mask); assertEquals(contextual,out.contextual);
        }
    }
    @Test void downwardOcclusionAppliesBelowTheBlockButNotToItsOwnBoundaryValue() {
        SkyColumnScanner.Reader reader=(y,out)->{air(out);out.blockedFaces=8;};
        var out=new SkyColumnCache.Input();
        assertEquals(1,SkyColumnScanner.scan(15,15,0xfff,reader,new CapturedRgbRegion.Input(),out)); assertEquals(0xfff,out.mask);
        assertEquals(1,SkyColumnScanner.scan(15,14,0xfff,reader,new CapturedRgbRegion.Input(),out)); assertEquals(0,out.mask);
    }
    @Test void randomizedPopulatedAndEmptySectionsMatchThePerBlockReference() {
        var random=new Random(20260913);
        for(int scenario=0;scenario<300;scenario++) {
            int top=random.nextInt(320),bottom=-32+random.nextInt(64);
            Set<Integer> populated=new HashSet<>(); for(int section=-2;section<20;section++) if(random.nextBoolean()) populated.add(section);
            int[] absorption=new int[352],faces=new int[352]; boolean[] dynamic=new boolean[352];
            for(int y=-32;y<320;y++) if(populated.contains(y>>4)) {
                absorption[y+32]=random.nextInt(10)==0?random.nextInt(4096):0;
                faces[y+32]=random.nextInt(40)==0?8:0; dynamic[y+32]=random.nextInt(30)==0;
            }
            var reader=new SkyColumnScanner.Reader() {
                public void read(int y,CapturedRgbRegion.Input out) { air(out);out.incoming[2]=absorption[y+32];out.blockedFaces=faces[y+32];out.contextual=dynamic[y+32]; }
                public int emptyBottom(int y) { return populated.contains(y>>4)?y:y&~15; }
            };
            int bits=random.nextInt(8),mask=((bits&1)!=0?0xf:0)|((bits&2)!=0?0xf0:0)|((bits&4)!=0?0xf00:0);
            var expected=reference(top,bottom,mask,reader); var actual=new SkyColumnCache.Input();
            SkyColumnScanner.scan(top,bottom,mask,reader,new CapturedRgbRegion.Input(),actual);
            assertEquals(expected.mask,actual.mask,"scenario "+scenario); assertEquals(expected.contextual,actual.contextual);
        }
    }
    @Test void extremeCutoffAndEmptyRangesDoNotOverflowOrReadBelowTheBoundary() {
        SkyColumnScanner.Reader reader=(y,out)->air(out); var out=new SkyColumnCache.Input();
        assertEquals(1,SkyColumnScanner.scan(Integer.MIN_VALUE,Integer.MIN_VALUE,0xfff,reader,new CapturedRgbRegion.Input(),out));
        assertEquals(0,SkyColumnScanner.scan(0,1,0xfff,reader,new CapturedRgbRegion.Input(),out)); assertEquals(0xfff,out.mask);
        assertEquals(0,SkyColumnScanner.scan(255,0,0,reader,new CapturedRgbRegion.Input(),out)); assertEquals(0,out.mask);
    }
}

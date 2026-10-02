package com.sumirelabs.pcla.light.engine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PackedWordRgbTest {
    @Test void allCanonicalPairsMatchIndependentScalarMath() {
        int[] word=new int[4096];
        for(int c=0;c<4096;c++) {
            word[c]=PackedWordRgb.fromRgb12(c);
            assertEquals((c&15)|((c>>>4&15)<<8)|((c>>>8&15)<<16),word[c]);
            assertEquals(c,PackedWordRgb.toRgb12(word[c]));
        }
        for(int a=0;a<4096;a++)for(int b=0;b<4096;b++) {
            int union=0,atten=0,dep=0;
            boolean greater=false;
            for(int sh=0;sh<=8;sh+=4) {
                int ca=a>>>sh&15,cb=b>>>sh&15;
                union|=Math.max(ca,cb)<<sh;
                atten|=Math.max(0,ca-Math.max(1,cb))<<sh;
                dep|=(ca<cb?ca:0)<<sh;
                greater|=ca>cb;
            }
            if(union!=PackedWordRgb.toRgb12(PackedWordRgb.union(word[a],word[b]))||atten!=PackedWordRgb.toRgb12(PackedWordRgb.attenuateBlock(word[a],word[b]))||dep!=PackedWordRgb.toRgb12(PackedWordRgb.dependentComponents(word[a],word[b]))||greater!=PackedWordRgb.anyGreater(word[a],word[b]))fail("pair "+a+","+b);
        }
    }
    @Test void validatesSparseWords() {
        assertThrows(IllegalArgumentException.class,()->PackedWordRgb.validate(0x10));
        assertThrows(IllegalArgumentException.class,()->PackedWordRgb.validate(-1));
        PackedWordRgb.validate(PackedWordRgb.MASK);
    }
}

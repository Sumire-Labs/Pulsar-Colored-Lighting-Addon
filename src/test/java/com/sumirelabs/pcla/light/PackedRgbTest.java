package com.sumirelabs.pcla.light;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackedRgbTest {
    @Test void allPackedPairsHaveIndependentComponents() {
        for (int a = 0; a < 4096; a++) for (int b = 0; b < 4096; b++) {
            int union = 0, attenuation = 0, dependent = 0;
            for (int shift = 0; shift <= 8; shift += 4) {
                int ca = a >> shift & 15, cb = b >> shift & 15;
                union |= Math.max(ca, cb) << shift;
                attenuation |= Math.max(0, ca - Math.max(1, cb)) << shift;
                dependent |= (ca < cb ? ca : 0) << shift;
            }
            if (union != PackedRgb.union(a, b) || attenuation != PackedRgb.attenuateBlock(a, b)
                    || dependent != PackedRgb.dependentComponents(a, b)) fail("Packed pair " + a + "," + b);
        }
    }
}

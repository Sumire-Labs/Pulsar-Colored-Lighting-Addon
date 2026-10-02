package com.sumirelabs.pcla.light;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RgbTest {
    @Test void allPackedValuesRoundTripWithoutChannelBleed() {
        for (int value = 0; value < 4096; value++) assertEquals(value, Rgb.unpack(value).packed());
    }
    @Test void attenuationNeverBorrowsFromAnotherChannel() {
        for (int level = 0; level < 16; level++) for (int cost = 0; cost < 16; cost++) {
            assertEquals(new Rgb(Math.max(0, level - cost), 9, 3), new Rgb(level, 9, 3).attenuate(new Rgb(cost, 0, 0)));
        }
    }
    @Test void coloredSourcesKeepTheirContextualEmissionMaximum() {
        for (int value = 1; value < 4096; value++) for (int level = 0; level < 16; level++)
            assertEquals(level, Rgb.unpack(value).atIntensity(level).max());
    }
    @Test void overlappingIndependentSourcesKeepBothComponents() {
        assertEquals(new Rgb(10, 0, 8), new Rgb(10, 0, 0).union(new Rgb(0, 0, 8)));
    }
    @Test void invalidComponentsAreRejectedRatherThanWrapped() {
        assertThrows(IllegalArgumentException.class, () -> new Rgb(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Rgb(0, 16, 0));
        assertThrows(IllegalArgumentException.class, () -> Rgb.unpack(4096));
    }
}

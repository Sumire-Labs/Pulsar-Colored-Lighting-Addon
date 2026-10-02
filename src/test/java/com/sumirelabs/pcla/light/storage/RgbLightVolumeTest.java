package com.sumirelabs.pcla.light.storage;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RgbLightVolumeTest {
    @Test void unchangedWritesDoNotPublishAnotherVolumeGeneration() {
        var volume = new RgbLightVolume(16, 33, 16);
        volume.set(4095, 0x123);
        var before = volume.publish();
        volume.set(4095, 0x123);
        volume.set(4096, 0);
        volume.set(8192, 0);
        assertSame(before, volume.publish());
        volume.set(8192, 0x456);
        var after = volume.publish();
        assertNotSame(before, after);
        assertEquals(0, before.get(8192));
        assertEquals(0x456, after.get(8192));
    }

    @Test void columnIndexingPreservesPartialTopSectionSnapshotsAndDiscard() {
        var volume = new RgbLightVolume(16, 33, 16);
        short[] expected = new short[volume.cellCount()];
        var reader = volume.updatingColumnReader();
        var random = new Random(4301);
        for (int cell = 0; cell < expected.length; cell++) {
            expected[cell] = (short) random.nextInt(4096);
            volume.set(cell, expected[cell]);
            assertEquals(volume.getUpdating(cell), reader.get(cell));
        }
        var original = volume.publish();
        short[] copied = new short[expected.length];
        original.copyTo(copied);
        assertArrayEquals(expected, copied);
        for (int cell = 0; cell < expected.length; cell++) {
            assertEquals(expected[cell], original.get(cell));
            volume.set(cell, 0);
            assertEquals(0, volume.getUpdating(cell));
            assertEquals(volume.getUpdating(cell), reader.get(cell));
            assertEquals(expected[cell], original.get(cell));
        }
        volume.discard();
        assertSame(original, volume.publish());
        for (int cell = 0; cell < expected.length; cell++) {
            assertEquals(expected[cell], volume.getUpdating(cell));
            assertEquals(volume.getUpdating(cell), reader.get(cell));
        }
        assertThrows(IndexOutOfBoundsException.class, () -> reader.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> reader.get(expected.length));
        for (int cell = 0; cell < expected.length; cell++) volume.set(cell, 0);
        volume.publish();
        for (int cell = 0; cell < expected.length; cell++) {
            assertEquals(0, reader.get(cell));
            assertEquals(expected[cell], original.get(cell));
        }
        assertThrows(IndexOutOfBoundsException.class, () -> original.get(expected.length));
        assertThrows(IndexOutOfBoundsException.class, () -> volume.set(-1, 0));
    }

    @Test void nonAlignedDimensionsAndSectionBoundariesMapEveryCellUniquely() {
        var volume = new RgbLightVolume(33, 19, 17);
        short[] expected = new short[volume.cellCount()];
        Random random = new Random(9139);
        for (int cell = 0; cell < expected.length; cell++) {
            expected[cell] = (short) random.nextInt(4096);
            volume.set(cell, expected[cell]);
        }
        var snapshot = volume.publish();
        for (int cell = 0; cell < expected.length; cell++) assertEquals(expected[cell], snapshot.get(cell));
        for (int cell = 0; cell < expected.length; cell++) volume.set(cell, 0);
        var empty = volume.publish();
        for (int cell = 0; cell < expected.length; cell++) {
            assertEquals(0, empty.get(cell));
            assertEquals(expected[cell], snapshot.get(cell));
        }
    }

    @Test void failedTaskCanBeDiscardedAcrossSections() {
        var volume = new RgbLightVolume(33, 17, 17);
        volume.set(15, 0xf00); volume.set(16, 0x00f);
        var before = volume.publish();
        volume.set(15, 0); volume.set(16, 0xfff); volume.set(volume.cellCount() - 1, 0x123);
        volume.discard();
        assertSame(before, volume.publish());
        assertEquals(0xf00, volume.getUpdating(15));
        assertEquals(0x00f, volume.getUpdating(16));
        assertEquals(0, volume.getUpdating(volume.cellCount() - 1));
    }

    @Test void concurrentReaderNeverSeesMixedSectionGenerations() throws Exception {
        var volume = new RgbLightVolume(33, 1, 1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var start = new CountDownLatch(1);
            Future<?> reader = executor.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                for (int read = 0; read < 10000; read++) {
                    var snapshot = volume.snapshot();
                    int color = snapshot.get(0);
                    for (int cell = 1; cell < snapshot.cellCount(); cell++) assertEquals(color, snapshot.get(cell));
                }
            });
            start.countDown();
            for (int generation = 1; generation <= 100; generation++) {
                for (int cell = 0; cell < volume.cellCount(); cell++) volume.set(cell, generation);
                volume.publish();
            }
            reader.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void rejectsInvalidDimensionsAndCells() {
        assertThrows(IllegalArgumentException.class, () -> new RgbLightVolume(15, 33, 16).updatingColumnReader());
        assertThrows(IllegalArgumentException.class, () -> new RgbLightVolume(16, 33, 17).updatingColumnReader());
        assertThrows(IllegalArgumentException.class, () -> new RgbLightVolume(0, 16, 16));
        assertThrows(ArithmeticException.class, () -> new RgbLightVolume(Integer.MAX_VALUE, 2, 1));
        var volume = new RgbLightVolume(16, 16, 16);
        assertThrows(IndexOutOfBoundsException.class, () -> volume.set(-1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> volume.snapshot().get(4096));
        assertThrows(IllegalArgumentException.class, () -> volume.set(0, 4096));
    }
}

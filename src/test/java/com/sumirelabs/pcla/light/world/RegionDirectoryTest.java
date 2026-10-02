package com.sumirelabs.pcla.light.world;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionDirectoryTest {
    private static CapturedRgbRegion.Bounds bounds(int x) { return new CapturedRgbRegion.Bounds(x,0,0); }
    private static <R> void load(RegionDirectory<Object,R> directory, CapturedRgbRegion.Bounds bounds) {
        for (long key : bounds.chunks()) directory.loaded(key,new Object());
    }
    @Test void positionNotificationsOnlyInvalidateIntersectingHalosWithTheirOwnCoordinates() {
        var directory = new RegionDirectory<Object,String>();
        var a = directory.replace(0,bounds(0));
        int slot = directory.add(bounds(16));
        var b = directory.get(slot);
        load(directory,bounds(0)); load(directory,bounds(16));
        assertTrue(a.gate().publish(a.gate().begin(),"a"));
        assertTrue(b.gate().publish(b.gate().begin(),"b"));
        directory.changedAt(2,2,2);
        assertNull(a.gate().snapshot()); assertNull(b.gate().snapshot());
        assertTrue(a.gate().begin().dirtyCells().get(a.bounds().inputCell(2,2,2)));
        assertTrue(b.gate().begin().dirtyCells().get(b.bounds().inputCell(2,2,2)));
        assertNotEquals(a.bounds().inputCell(2,2,2),b.bounds().inputCell(2,2,2));
        a.gate().publish(a.gate().begin(),"a"); b.gate().publish(b.gate().begin(),"b");
        directory.changedAt(0,2,2); // Same chunk as both inputs, outside b's exact halo.
        assertNull(a.gate().snapshot()); assertEquals("b",b.gate().snapshot());
        assertFalse(directory.containsInput(1000,2,2));
        assertTrue(directory.affected(RegionPublication.key(1000,1000)).isEmpty());
    }
    @Test void unloadReplacementAndCoarseInvalidationPreserveUnrelatedRegions() {
        var directory = new RegionDirectory<Object,String>();
        load(directory,bounds(0)); // Chunks loaded before subscription are usable.
        var a = directory.replace(0,bounds(0));
        var b = directory.replace(1,bounds(128)); load(directory,bounds(128));
        var old = a.gate().begin();
        a.gate().publish(old,"a"); b.gate().publish(b.gate().begin(),"b");
        long key = RegionPublication.key(0,0);
        directory.unloaded(key);
        assertNull(a.gate().begin()); assertEquals("b",b.gate().snapshot());
        assertFalse(a.gate().publish(old,"late"));
        directory.loaded(key,new Object());
        assertTrue(a.gate().begin().fullCapture());
        assertFalse(a.gate().publish(old,"replaced"));
        assertTrue(a.gate().publish(a.gate().begin(),"new"));
        directory.changed(key);
        assertTrue(a.gate().begin().fullCapture()); assertNull(a.gate().snapshot());
        assertEquals("b",b.gate().snapshot());
    }
    @Test void retiredInFlightWorkerCannotPublishIntoReusedSlot() throws Exception {
        var directory = new RegionDirectory<Object,String>();
        load(directory,bounds(0));
        var old = directory.replace(0,bounds(0));
        var ticket = old.gate().begin();
        var proceed = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var late = executor.submit(() -> { proceed.await(); return old.gate().publish(ticket,"stale"); });
            try {
                var replacement = directory.replace(0,bounds(0));
                assertNotSame(old,replacement);
                assertTrue(replacement.gate().publish(replacement.gate().begin(),"current"));
                proceed.countDown();
                assertFalse(late.get(5,TimeUnit.SECONDS));
                assertEquals("current",directory.get(0).gate().snapshot());
                assertNull(old.gate().begin());
            } finally { proceed.countDown(); }
        }
    }
    @Test void subscriptionsAreBoundedAndRemovalClearsRoutingAndRejectsLateResults() {
        var directory = new RegionDirectory<Object,String>();
        load(directory,bounds(0));
        directory.replace(0,bounds(0));
        for (int slot = 1; slot < RegionDirectory.MAX_REGIONS; slot++) assertEquals(slot,directory.add(bounds(0)));
        assertThrows(IllegalStateException.class,() -> directory.add(bounds(0)));
        var old = directory.get(1); var ticket = old.gate().begin();
        assertTrue(directory.remove(1)); assertFalse(directory.remove(1));
        assertFalse(old.gate().publish(ticket,"late"));
        assertFalse(directory.affected(RegionPublication.key(0,0)).contains(old));
        assertEquals(1,directory.add(bounds(128)));
        assertThrows(UnsupportedOperationException.class,() -> directory.entries().clear());
        assertThrows(IllegalArgumentException.class,() -> directory.replace(4,bounds(0)));
        assertThrows(NullPointerException.class,() -> directory.replace(0,null));
        assertNotNull(directory.get(0));
        var remaining = directory.entries();
        directory.close();
        assertTrue(directory.entries().isEmpty());
        assertTrue(directory.affected(RegionPublication.key(0,0)).isEmpty());
        assertFalse(directory.containsInput(0,0,0));
        for (var entry : remaining) assertNull(entry.gate().begin());
        directory.loaded(0,new Object()); directory.changed(0); directory.changedAt(0,0,0); directory.unloaded(0);
        assertThrows(IllegalStateException.class,() -> directory.replace(0,bounds(0)));
        assertThrows(IllegalStateException.class,() -> directory.add(bounds(0)));
    }
}

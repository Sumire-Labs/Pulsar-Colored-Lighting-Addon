package com.sumirelabs.pcla.light.storage;
import com.sumirelabs.pcla.light.engine.PackedWordRgb;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WordVolumeTest {
    @Test void nativeWritersCanonicalSnapshotsPartialTopDiscardAndHistory() {
        var v=new RgbLightVolume.WordColumnVolume(33);
        var r=new Random(46024);
        short[] expected=new short[8448];
        for(int i=0;i<expected.length;i++) {
            expected[i]=(short)r.nextInt(4096);
            v.setUpdatingWord(i,PackedWordRgb.fromRgb12(expected[i]));
            assertEquals(expected[i],PackedWordRgb.toRgb12(v.getUpdatingWord(i)));
        }
        var old=v.publish();
        short[] copy=new short[8448];
        old.copyTo(copy);
        assertArrayEquals(expected,copy);
        for(int i=0;i<expected.length;i++)v.setRgb12(i,0);
        v.discard();
        assertSame(old,v.publish());
        for(int i=0;i<expected.length;i++)assertEquals(expected[i],PackedWordRgb.toRgb12(v.getUpdatingWord(i)));
        for(int i=0;i<expected.length;i++)v.setRgb12(i,0);
        var empty=v.publish();
        for(int i=0;i<expected.length;i++) {
            assertEquals(0,empty.get(i));
            assertEquals(expected[i],old.get(i));
        }
        assertSame(empty,v.publish());
        assertThrows(IndexOutOfBoundsException.class,()->v.getUpdatingWord(8448));
        assertThrows(IndexOutOfBoundsException.class,()->v.setRgb12(-1,0));
        assertThrows(IllegalArgumentException.class,()->v.setUpdatingWord(0,0x10));
        assertThrows(IllegalArgumentException.class,()->v.setRgb12(0,4096));
        assertSame(empty,v.publish());
    }
    @Test void canceledWritesRetainSectionSnapshotButPublishNewVolumeRoot() {
        var v=new RgbLightVolume.WordColumnVolume(16);
        v.setRgb12(0,0x123);
        var old=v.publish();
        v.setRgb12(0,0);
        v.setRgb12(0,0x123);
        var next=v.publish();
        assertNotSame(old,next);
        assertSame(old.section(0),next.section(0));
        assertEquals(0x123,next.get(0));
    }
    @Test void uniformPagesSharedWithGenericCanonicalSnapshotsAndWriterNeverEscapes() throws Exception {
        var v=new RgbLightVolume.WordColumnVolume(16);
        var s=new RgbSection();
        short[] values=new short[4096];
        Arrays.fill(values,(short)0xabc);
        s.replace(values);
        var generic=s.publish();
        for(int i=0;i<4096;i++)v.setRgb12(i,0xabc);
        var nativeSnap=v.publish().section(0);
        var f=RgbSection.Snapshot.class.getDeclaredField("pages");
        f.setAccessible(true);
        assertSame(((short[][])f.get(generic))[0],((short[][])f.get(nativeSnap))[0]);
        v.setRgb12(0,0);
        v.publish();
        assertEquals(0xabc,nativeSnap.get(0));
    }
    @Test void lateNonzeroAndEqualDirtyPagesPreserveIdentityAndHistory() {
        var v=new RgbLightVolume.WordColumnVolume(33);
        v.setRgb12(63,0x123);
        v.setRgb12(8447,0x456);
        var old=v.publish();
        assertEquals(0,old.get(0));
        assertEquals(0x123,old.get(63));
        v.setRgb12(63,0x789);
        v.setRgb12(63,0x123);
        v.setRgb12(8447,0);
        v.setRgb12(8447,0x456);
        var restored=v.publish();
        assertNotSame(old,restored);
        assertSame(old.section(0),restored.section(0));
        assertSame(old.section(2),restored.section(2));
        for(int i=0;i<64;i++)v.setRgb12(i,0xaaa);
        var uniform=v.publish();
        for(int i=0;i<64;i++)assertEquals(0xaaa,uniform.get(i));
        v.setRgb12(63,0xbbb);
        var mixed=v.publish();
        assertEquals(0xaaa,mixed.get(62));
        assertEquals(0xbbb,mixed.get(63));
        assertEquals(0xaaa,uniform.get(63));
        for(int i=0;i<64;i++)v.setRgb12(i,0);
        v.setRgb12(8447,0);
        var zero=v.publish();
        assertEquals(0,zero.get(63));
        assertEquals(0,zero.get(8447));
        assertEquals(0x456,old.get(8447));
    }
    @Test void concurrentReadersRetainOneCoherentCanonicalRoot() throws Exception {
        var volume = new RgbLightVolume.WordColumnVolume(32);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var reader = executor.submit(() -> {
                try {
                    start.await();
                }
                catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                }
                for (int read = 0; read < 10000; read++) {
                    var snapshot = volume.snapshot(); int color = snapshot.get(0); assertEquals(color, snapshot.get(4096));
                }
            }
            );
            start.countDown();
            for (int generation = 1; generation <= 100; generation++) {
                volume.setRgb12(0, generation);
                volume.setRgb12(4096, generation);
                volume.publish();
            }
            reader.get();
        }
    }
}

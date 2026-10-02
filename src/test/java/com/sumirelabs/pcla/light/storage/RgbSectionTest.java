package com.sumirelabs.pcla.light.storage;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RgbSectionTest {
    private static short[] writerBuffer(RgbSection section) throws Exception {
        var field=RgbSection.class.getDeclaredField("updating");field.setAccessible(true);
        return (short[])field.get(section);
    }
    private static short[][] readerPages(RgbSection.Snapshot snapshot) throws Exception {
        var field=RgbSection.Snapshot.class.getDeclaredField("pages");field.setAccessible(true);
        return (short[][])field.get(snapshot);
    }

    @Test void uniformPagesAreSharedAcrossSectionsButNeverWithMutableWriters() throws Exception {
        var a=new RgbSection();var b=new RgbSection();short[] values=new short[4096];Arrays.fill(values,(short)0xfff);
        a.replace(values);b.replace(values);var firstA=a.publish();var firstB=b.publish();
        var pagesA=readerPages(firstA);var pagesB=readerPages(firstB);short[] white=pagesA[0];
        for(int page=0;page<64;page++){assertSame(white,pagesA[page]);assertSame(white,pagesB[page]);}
        assertNotSame(writerBuffer(a),writerBuffer(b));assertNotSame(white,writerBuffer(a));assertNotSame(white,writerBuffer(b));
        a.set(0,0x123);var changed=a.publish();assertNotSame(white,readerPages(changed)[0]);assertSame(white,readerPages(changed)[1]);
        assertEquals(0xfff,firstA.get(0));assertEquals(0xfff,firstB.get(0));assertEquals(0x123,changed.get(0));
        a.clear();a.publish();b.set(4095,0);b.discard();
        assertEquals(0xfff,firstA.get(4095));assertEquals(0xfff,firstB.get(4095));assertEquals(0xfff,b.getUpdating(4095));
        for(short value:white)assertEquals(0xfff,value);
    }

    @Test void zeroAndMixedPagesDoNotAliasInternedUniformData() throws Exception {
        var uniform=new RgbSection();for(int cell=0;cell<64;cell++)uniform.set(cell,0x9ab);
        short[] shared=readerPages(uniform.publish())[0];
        var mixed=new RgbSection();for(int cell=0;cell<64;cell++)mixed.set(cell,0x9ab);mixed.set(63,0x123);
        var snapshot=mixed.publish();assertNotSame(shared,readerPages(snapshot)[0]);
        assertEquals(0x123,snapshot.get(63));assertEquals(0x9ab,shared[63]);
        mixed.clear();var empty=mixed.publish();for(short[] page:readerPages(empty))assertNull(page);
        var field=RgbSection.class.getDeclaredField("UNIFORM_PAGES");field.setAccessible(true);
        var cache=(java.util.concurrent.atomic.AtomicReferenceArray<?>)field.get(null);
        assertEquals(4096,cache.length());assertNull(cache.get(0));
        assertEquals(0x123,snapshot.get(63));
    }

    @Test void concurrentWritersPublishTheSameCompleteUniformPage() throws Exception {
        try(var executor=Executors.newFixedThreadPool(8)) {
            var ready=new CountDownLatch(8);var start=new CountDownLatch(1);var results=new ArrayList<Future<RgbSection.Snapshot>>();
            for(int task=0;task<8;task++)results.add(executor.submit(()->{
                var section=new RgbSection();for(int cell=4032;cell<4096;cell++)section.set(cell,0x6a4);
                ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));var old=section.publish();
                section.set(4095,0x111);section.publish();return old;
            }));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();short[] canonical=null;
            for(var result:results) {
                var snapshot=result.get(10,TimeUnit.SECONDS);short[] page=readerPages(snapshot)[63];
                if(canonical==null)canonical=page;else assertSame(canonical,page);
                for(int cell=4032;cell<4096;cell++)assertEquals(0x6a4,snapshot.get(cell));
                assertEquals(0,snapshot.get(4031));
            }
        }
    }

    @Test void writerIsLazyPrivateAndReusedAcrossZeroLightPublications() throws Exception {
        var section=new RgbSection();var emptyBefore=section.snapshot();
        section.set(0,0);section.set(4095,0);assertNull(writerBuffer(section));
        section.set(0,0xf21);short[] writer=writerBuffer(section);assertEquals(4096,writer.length);
        section.set(4095,0x38f);assertSame(writer,writerBuffer(section));
        var first=section.publish();
        assertNotSame(writer,readerPages(first)[0]);assertNotSame(writer,readerPages(first)[63]);
        section.set(0,0);section.set(4095,0);
        assertEquals(0,section.getUpdating(0));assertEquals(0,section.getUpdating(4095));
        assertEquals(0xf21,first.get(0));assertEquals(0x38f,first.get(4095));
        var empty=section.publish();
        assertNull(readerPages(empty)[0]);assertNull(readerPages(empty)[63]);
        assertSame(writer,writerBuffer(section),"Zero publication retains the private writer until section retirement");
        section.set(64,0x777);assertSame(writer,writerBuffer(section));
        assertEquals(0,emptyBefore.get(64));assertEquals(0,empty.get(64));
        assertEquals(0x777,section.publish().get(64));
    }

    @Test void publicationSharesUntouchedPagesAndReusesIdentityForCancelledWrites() throws Exception {
        var section=new RgbSection();section.set(0,0x123);section.set(64,0x456);section.set(4095,0x789);
        var first=section.publish();var firstPages=readerPages(first);
        section.set(0,0xfff);section.set(0,0x123);
        assertSame(first,section.publish(),"Net-zero edits must not publish a replacement root");
        section.set(0,0x321);var second=section.publish();var secondPages=readerPages(second);
        assertNotSame(firstPages[0],secondPages[0]);assertSame(firstPages[1],secondPages[1]);assertSame(firstPages[63],secondPages[63]);
        assertEquals(0x123,first.get(0));assertEquals(0x321,second.get(0));
        short[] writer=writerBuffer(section);
        section.set(4095,0);section.set(4032,0xfff);section.discard();
        assertSame(writer,writerBuffer(section));assertSame(second,section.publish());
        assertEquals(0x789,section.getUpdating(4095));assertEquals(0,section.getUpdating(4032));
    }

    @Test void clearReplaceAndRollbackCoverTheHighestDirtyPageWithoutAliasing() throws Exception {
        var section=new RgbSection();section.set(0,0xf00);section.set(4095,0x123);var first=section.publish();
        short[] writer=writerBuffer(section);section.clear();section.discard();
        assertSame(first,section.publish());assertEquals(0xf00,section.getUpdating(0));assertEquals(0x123,section.getUpdating(4095));
        short[] replacement=new short[4096];replacement[4033]=0x789;
        section.replace(replacement);replacement[4033]=0;
        assertSame(writer,writerBuffer(section));assertEquals(0x789,section.getUpdating(4033));
        var second=section.publish();assertEquals(0x789,second.get(4033));assertEquals(0,second.get(4095));assertEquals(0,second.get(0));
        assertEquals(0xf00,first.get(0));assertEquals(0x123,first.get(4095));
        section.set(4033,0);section.discard();assertEquals(0x789,section.getUpdating(4033));
        section.clear();var empty=section.publish();assertEquals(0,empty.get(4033));assertSame(writer,writerBuffer(section));
    }
    @Test void snapshotsRemainStableAcrossEditsClearsAndReloads() {
        var section = new RgbSection();
        var empty = section.snapshot();
        section.set(0, 0xf00); section.set(63, 0x00f); section.set(64, 0x0f0); section.set(4095, 0xfff);
        assertEquals(0, empty.get(0));
        var first = section.publish();
        section.set(0, 0x777); section.set(63, 0); section.set(64, 0); section.set(4095, 0);
        assertEquals(0xf00, first.get(0));
        assertEquals(0x00f, first.get(63));
        assertEquals(0x0f0, first.get(64));
        var second = section.publish();
        section.clear(); section.publish();
        assertEquals(0x777, second.get(0));
        assertEquals(0, section.snapshot().get(0));
        short[] data = new short[4096]; first.copyTo(data);
        section.replace(data); data[0] = 1;
        assertEquals(0xf00, section.getUpdating(0));
        assertEquals(0, section.snapshot().get(0));
        assertEquals(0xf00, section.publish().get(0));
        assertEquals(0, empty.get(4095));
    }

    @Test void noOpWritesReuseSnapshotAndDiscardRollsBackUnpublishedChanges() {
        var section = new RgbSection();
        section.set(10, 0x123);
        var before = section.publish();
        section.set(10, 0x123); section.set(2000, 0);
        assertSame(before, section.publish());
        section.set(10, 0); section.set(11, 0xfff); section.set(4000, 0x456);
        section.discard();
        assertSame(before, section.publish());
        assertEquals(0x123, section.getUpdating(10));
        assertEquals(0, section.getUpdating(11));
        assertEquals(0, section.getUpdating(4000));
        section.set(10, 0x321);
        assertEquals(0x123, before.get(10));
        assertEquals(0x321, section.publish().get(10));
    }

    @Test void randomizedEditsMatchFlatStorageWithoutMutatingHistoricalSnapshots() {
        var section = new RgbSection();
        Random random = new Random(91);
        short[] expected = new short[4096];
        var histories = new ArrayList<RgbSection.Snapshot>();
        var copies = new ArrayList<short[]>();
        for (int round = 0; round < 100; round++) {
            for (int edit = 0; edit < 150; edit++) {
                int cell = random.nextInt(4096), color = random.nextBoolean() ? 0 : random.nextInt(4096);
                section.set(cell, color); expected[cell] = (short) color;
            }
            histories.add(section.publish()); copies.add(expected.clone());
        }
        short[] actual = new short[4096];
        for (int i = 0; i < histories.size(); i++) {
            histories.get(i).copyTo(actual);
            assertArrayEquals(copies.get(i), actual);
        }
    }

    @Test void invalidReplacementDoesNotPartiallyEraseValidData() {
        var section = new RgbSection();
        section.set(0, 0x123); section.publish();
        short[] bad = new short[4096]; bad[4095] = 0x1000;
        assertThrows(IllegalArgumentException.class, () -> section.replace(bad));
        assertEquals(0x123, section.getUpdating(0));
        assertThrows(IllegalArgumentException.class, () -> section.replace(new short[4095]));
        assertThrows(IllegalArgumentException.class, () -> section.set(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> section.set(4096, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> section.snapshot().get(-1));
    }

    @Test void concurrentReaderSeesCompleteImmutableSectionPublications() throws Exception {
        var section = new RgbSection();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var start = new CountDownLatch(1);
            Future<?> reader = executor.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                for (int read = 0; read < 300; read++) {
                    var snapshot = section.snapshot();
                    int expected = snapshot.get(0);
                    for (int cell = 1; cell < 4096; cell++) assertEquals(expected, snapshot.get(cell));
                }
            });
            start.countDown();
            for (int generation = 1; generation <= 50; generation++) {
                for (int cell = 0; cell < 4096; cell++) section.set(cell, generation);
                section.publish();
            }
            reader.get(10, TimeUnit.SECONDS);
        }
    }
}

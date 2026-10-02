package com.sumirelabs.pcla.light.storage;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotExportTest {
    @Test void exportedRowsMatchScalarReadsForPartialAndMultipleSections() {
        var random=new Random(20260913);
        for(int[] shape:new int[][]{{1,1,1},{16,16,16},{17,19,23},{32,32,32},{46,3,17}}) {
            var volume=new RgbLightVolume(shape[0],shape[1],shape[2]);
            short[] expected=new short[volume.cellCount()];
            for(int i=0;i<expected.length;i++) if(random.nextInt(11)==0) {
                expected[i]=(short)random.nextInt(4096); volume.set(i,expected[i]);
            }
            var snapshot=volume.publish(); short[] actual=new short[expected.length];
            Arrays.fill(actual,(short)-1); snapshot.copyTo(actual); assertArrayEquals(expected,actual);
            for(int i=0;i<actual.length;i++) assertEquals(snapshot.get(i),actual[i]);
            // All zero output must overwrite caller storage even when no section has ever existed.
            new RgbLightVolume(shape[0],shape[1],shape[2]).snapshot().copyTo(actual);
            for(short value:actual) assertEquals(0,value);
        }
    }

    @Test void retainedSnapshotAndReturnedBuffersStayIndependentAcrossPublicationAndDiscard() {
        var volume=new RgbLightVolume(32,32,32); volume.set(63,0xf00); volume.set(4096,0x00f);
        var old=volume.publish(); short[] baseline=new short[volume.cellCount()]; old.copyTo(baseline);
        volume.set(63,0); volume.set(4096,0x0f0); volume.publish();
        short[] retained=new short[baseline.length]; old.copyTo(retained); assertArrayEquals(baseline,retained);
        retained[63]=0xfff; assertEquals(0xf00,old.get(63));
        volume.set(8192,0xfff); volume.discard();
        volume.snapshot().copyTo(retained); assertEquals(0,retained[8192]); assertEquals(0x0f0,retained[4096]);
        assertThrows(IllegalArgumentException.class,()->old.copyTo(new short[2]));
    }

    @Test void sectionRangesCrossPagesAndZeroPagesWithoutTouchingOutsideTheRange() {
        var section=new RgbSection(); section.set(63,0xf00); section.set(64,0x0f0); section.set(191,0x00f);
        var snapshot=section.publish(); short[] result=new short[220]; Arrays.fill(result,(short)-1);
        snapshot.copyTo(60,result,5,140);
        for(int i=0;i<result.length;i++) assertEquals(i>=5 && i<145 ? snapshot.get(i-5+60) : -1,result[i]);
        snapshot.copyTo(4096,result,result.length,0);
        assertThrows(IndexOutOfBoundsException.class,()->snapshot.copyTo(4095,result,0,2));
        assertThrows(IndexOutOfBoundsException.class,()->snapshot.copyTo(0,result,219,2));
        assertThrows(IndexOutOfBoundsException.class,()->snapshot.copyTo(-1,result,0,0));
    }

    @Test void bulkExportsKeepBothWireProtocolsByteForByteIdentical() {
        var bounds=new com.sumirelabs.pcla.light.world.CapturedRgbRegion.Bounds(-32,80,-16,2,2,2);
        var volume=new RgbLightVolume(32,32,32);
        for(int i=0;i<volume.cellCount();i+=113) volume.set(i,(i*29+7)&4095);
        var root=volume.publish(); short[] old=new short[root.cellCount()],bulk=new short[root.cellCount()];
        for(int i=0;i<old.length;i++) old[i]=(short)root.get(i);
        root.copyTo(bulk); var session=java.util.UUID.randomUUID();
        for(int protocol:new int[]{1,2}) {
            var a=io.netty.buffer.Unpooled.buffer(); var b=io.netty.buffer.Unpooled.buffer();
            try {
                if(protocol==1) {
                    com.sumirelabs.pcla.network.RegionFrameCodec.encode(com.sumirelabs.pcla.network.RegionFrame.upsert(session,1,1,0,0,bounds,old),a);
                    com.sumirelabs.pcla.network.RegionFrameCodec.encode(com.sumirelabs.pcla.network.RegionFrame.upsert(session,1,1,0,0,bounds,bulk),b);
                } else {
                    com.sumirelabs.pcla.network.RegionFrameV2Codec.encode(com.sumirelabs.pcla.network.RegionFrame.upsertV2(session,1,1,0,0,bounds,old,old),a);
                    com.sumirelabs.pcla.network.RegionFrameV2Codec.encode(com.sumirelabs.pcla.network.RegionFrame.upsertV2(session,1,1,0,0,bounds,bulk,bulk),b);
                }
                assertEquals(a,b,"protocol="+protocol);
            } finally { a.release(); b.release(); }
        }
    }
}

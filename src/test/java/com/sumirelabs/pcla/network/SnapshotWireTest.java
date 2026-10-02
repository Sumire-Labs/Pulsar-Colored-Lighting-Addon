package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.storage.RgbLightVolume;
import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import io.netty.buffer.Unpooled;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotWireTest {
    static final UUID ID=new UUID(12,34);
    static final CapturedRgbRegion.Bounds B=new CapturedRgbRegion.Bounds(-16,-64,32,1,3,1);
    static short[] copy(RgbLightVolume.Snapshot s) {if(s==null)return null;var a=new short[s.cellCount()];s.copyTo(a);return a;}
    static byte[] bytes(RegionFrame f) {
        var b=Unpooled.buffer();try {if(f.protocol==4)RegionFrameV4Codec.encode(RegionFrameV4Codec.Packet.full(f),b);else RegionFrameV3Codec.encode(f,b);
            var a=new byte[b.readableBytes()];b.readBytes(a);return a;}finally {b.release();}
    }
    static byte[] bytes(SectionDelta d) {
        var b=Unpooled.buffer();try {RegionFrameV4Codec.encode(RegionFrameV4Codec.Packet.patch(d),b);var a=new byte[b.readableBytes()];b.readBytes(a);return a;}finally {b.release();}
    }
    static RegionFrame owned(int p,long seq,RgbLightVolume.Snapshot b,RgbLightVolume.Snapshot s) {
        return p==4?RegionFrame.upsertV4(ID,1,seq,0,4,B,copy(b),copy(s)):RegionFrame.upsertV3(ID,1,seq,0,4,B,copy(b),copy(s));
    }
    @Test void delayedFullSerializationAndSamplingMatchDefensiveFramesForEveryLaneCombination() throws Exception {
        for(int lanes=1;lanes<=3;lanes++)for(int protocol=3;protocol<=4;protocol++) {
            var b=new RgbLightVolume.WordColumnVolume(48);var s=new RgbLightVolume(16,48,16);
            b.setRgb12(4097,0x123);s.set(8194,0xf00);var br=(lanes&1)==0?null:b.publish();var sr=(lanes&2)==0?null:s.publish();
            var shared=RegionFrame.upsertSnapshot(protocol,ID,1,1,0,4,B,br,sr);var expected=owned(protocol,1,br,sr);
            byte[] frozen=bytes(expected);
            var release=new java.util.concurrent.CountDownLatch(1);
            var delayed=java.util.concurrent.CompletableFuture.supplyAsync(()-> {
                try {release.await();return bytes(shared);}catch(InterruptedException failure) {throw new AssertionError(failure);}
            });
            for(int n=0;n<100;n++){b.setRgb12(4097,n);s.set(8194,n);b.publish();s.publish();}
            release.countDown();assertArrayEquals(frozen,delayed.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertArrayEquals(frozen,bytes(shared));
            for(int i=0;i<B.outputCells();i++){assertEquals(expected.color(i),shared.color(i));assertEquals(expected.skyColor(i),shared.skyColor(i));}
            assertEquals(expected.sample(-15,-48,32),shared.sample(-15,-48,32));
            assertEquals((lanes&1)==0?-1:0,shared.section(0,0,0).block(0));
            assertEquals((lanes&2)==0?-1:0,shared.section(0,0,0).sky(0));
        }
    }
    @Test void patchRetainsZeroChangesAndUnchangedLaneIdentityAfterDelayedSerialization() throws Exception {
        for(int lanes=1;lanes<=3;lanes++) {
            var b=new RgbLightVolume.WordColumnVolume(48);var s=new RgbLightVolume(16,48,16);
            b.setRgb12(7,0x123);s.set(4100,0xabc);var ob=(lanes&1)==0?null:b.publish();var os=(lanes&2)==0?null:s.publish();
            var baseline=RegionFrame.upsertSnapshot(4,ID,1,1,0,4,B,ob,os);
            b.setRgb12(7,0);s.set(4100,0x456);var nb=(lanes&1)==0?null:b.publish();var ns=(lanes&2)==0?null:s.publish();
            var d=SectionDelta.between(ID,1,2,1,0,4,B,ob,os,nb,ns);
            short[][] ba=new short[3][],sa=new short[3][];
            if(nb!=null)ba[0]=new short[4096];if(ns!=null){sa[1]=new short[4096];ns.section(1).copyTo(sa[1]);}
            var defensive=new SectionDelta(ID,1,2,1,0,4,B,lanes,ba,sa);byte[] frozen=bytes(defensive);
            for(int n=0;n<40;n++){b.setRgb12(7,n+1);s.set(4100,n);b.publish();s.publish();}
            assertArrayEquals(frozen,bytes(d));var patched=d.apply(baseline);
            assertSame(baseline.section(0,2,0),patched.section(0,2,0));
            assertArrayEquals(bytes(owned(4,2,nb,ns)),bytes(patched));
            var field=RegionFrame.Section.class.getDeclaredField(lanes==2?"block":"sky");field.setAccessible(true);
            assertSame(field.get(baseline.section(0,0,0)),field.get(patched.section(0,0,0)));
            assertFalse(d.matches(RegionFrame.upsertSnapshot(4,ID,1,3,0,4,B,nb,ns)));
        }
    }
    @Test void boundsLanePresenceAndPublicCallerArraysRemainValidated() {
        var root=new RgbLightVolume.WordColumnVolume(48).snapshot();
        assertThrows(IllegalArgumentException.class,()->RegionFrame.upsertSnapshot(2,ID,1,1,0,4,B,root,null));
        assertThrows(IllegalArgumentException.class,()->RegionFrame.upsertSnapshot(4,ID,1,1,0,3,B,root,null));
        assertThrows(IllegalArgumentException.class,()->RegionFrame.upsertSnapshot(4,ID,1,1,0,4,B,null,null));
        assertThrows(IllegalArgumentException.class,()->RegionFrame.upsertSnapshot(4,ID,1,1,0,4,B,new RgbLightVolume.WordColumnVolume(32).snapshot(),null));
        var wide=new CapturedRgbRegion.Bounds(0,0,0,2,3,1);
        assertThrows(IllegalArgumentException.class,()->RegionFrame.upsertSnapshot(4,ID,1,1,0,4,wide,root,null));
        var two=new CapturedRgbRegion.Bounds(0,0,0,1,2,1);
        var wrongGeometry=new RgbLightVolume(16,16,32).snapshot();var proper=new RgbLightVolume(16,32,16).snapshot();
        assertThrows(IllegalArgumentException.class,()->RegionFrame.upsertSnapshot(4,ID,1,1,0,4,two,wrongGeometry,null));
        assertThrows(IllegalArgumentException.class,()->SectionDelta.between(ID,1,2,1,0,4,two,wrongGeometry,null,proper,null));
        assertThrows(IllegalArgumentException.class,()->SectionDelta.between(ID,1,2,1,0,4,two,proper,null,wrongGeometry,null));
        short[] a=new short[B.outputCells()];a[0]=0x123;var f=RegionFrame.upsertV4(ID,1,1,0,4,B,a,null);a[0]=0;
        assertEquals(0x123,f.color(0));short[][] changed=new short[3][],absent=new short[3][];changed[0]=new short[4096];changed[0][0]=0x456;
        var d=new SectionDelta(ID,1,2,1,0,4,B,1,changed,absent);changed[0][0]=0;assertEquals(0x456,d.value(false,0,0));
        assertThrows(IllegalArgumentException.class,()->new SectionDelta(ID,1,2,1,0,4,B,2,changed,absent));
        assertThrows(IllegalArgumentException.class,()->SectionDelta.between(ID,1,2,1,0,4,B,root,null,null,root));
    }
}

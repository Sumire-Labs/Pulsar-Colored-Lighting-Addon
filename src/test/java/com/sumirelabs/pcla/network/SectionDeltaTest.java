package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.storage.RgbLightVolume;
import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import io.netty.buffer.Unpooled;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionDeltaTest {
    private static final String PROFILE="b".repeat(64);
    private static final CapturedRgbRegion.Bounds BOUNDS=new CapturedRgbRegion.Bounds(-32,0,-16,1,16,1);
    private static short[] data(RgbLightVolume.Snapshot root){if(root==null)return null;short[] data=new short[root.cellCount()];root.copyTo(data);return data;}
    private static RegionFrame full(UUID id,long sequence,RgbLightVolume.Snapshot block,RgbLightVolume.Snapshot sky){return RegionFrame.upsertV4(id,1,sequence,0,4,BOUNDS,data(block),data(sky));}
    private static SectionDelta delta(UUID id,long sequence,long base,RgbLightVolume.Snapshot oldBlock,RgbLightVolume.Snapshot oldSky,RgbLightVolume.Snapshot block,RgbLightVolume.Snapshot sky){return SectionDelta.between(id,1,sequence,base,0,4,BOUNDS,oldBlock,oldSky,block,sky);}
    private static RegionFrameV4Codec.Packet roundTrip(RegionFrameV4Codec.Packet packet) {
        var bytes=Unpooled.buffer();try{RegionFrameV4Codec.encode(packet,bytes);if(packet.delta()!=null)assertEquals(packet.delta().encodedBytes(),bytes.readableBytes());return RegionFrameV4Codec.decode(bytes);}finally{bytes.release();}
    }
    @Test void oneBlockSectionNeeds8260BytesAndKeepsUntouchedSections() {
        var id=UUID.randomUUID();var block=new RgbLightVolume(16,256,16);var sky=new RgbLightVolume(16,256,16);
        var before=block.snapshot();var old=full(id,1,before,sky.snapshot());block.set(3*4096+13,0xf30);var after=block.publish();
        var delta=roundTrip(RegionFrameV4Codec.Packet.patch(delta(id,2,1,before,sky.snapshot(),after,sky.snapshot()))).delta();
        assertEquals(1<<3,delta.blockMask);assertEquals(0,delta.skyMask);assertEquals(8260,delta.encodedBytes());
        var next=delta.apply(old);assertEquals(0,old.color(3*4096+13));assertEquals(0xf30,next.color(3*4096+13));
        assertSame(old.section(0,2,0),next.section(0,2,0));assertNotSame(old.section(0,3,0),next.section(0,3,0));
        assertEquals(FrameDigest.sha256(full(id,2,after,sky.snapshot())),FrameDigest.sha256(next));
        assertEquals(FrameDigest.skySha256(old),FrameDigest.skySha256(next));
        assertEquals(0xf30,next.sample(-19,48,-16));
    }
    @Test void pairedSkyAndBlockPatchIsAtomicAndItsPayloadIsDefensive() {
        var id=UUID.randomUUID();var block=new RgbLightVolume(16,256,16);var sky=new RgbLightVolume(16,256,16);
        var old=full(id,1,block.snapshot(),sky.snapshot());short[][] b=new short[16][],s=new short[16][];b[2]=new short[4096];s[12]=new short[4096];b[2][7]=0xf00;s[12][19]=0x00f;
        var delta=new SectionDelta(id,1,2,1,0,4,BOUNDS,3,b,s);b[2][7]=0;s[12][19]=0;
        var store=new ClientRegionStore();store.apply(RegionFrame.helloV4(id,1,0,PROFILE),0);store.apply(old,0);var held=store.snapshot();
        assertEquals(ClientRegionStore.DeltaResult.APPLIED,store.applyDelta(roundTrip(RegionFrameV4Codec.Packet.patch(delta)).delta(),0));
        assertEquals(0,held.regions().get(4).color(2*4096+7));assertEquals(0,held.regions().get(4).skyColor(12*4096+19));
        assertEquals(0xf00,store.snapshot().regions().get(4).color(2*4096+7));assertEquals(0x00f,store.snapshot().regions().get(4).skyColor(12*4096+19));
    }
    @Test void randomizedPatchChainsMatchFullSnapshotsAndCanReturnToZero() {
        var id=UUID.randomUUID();var rng=new Random(49128);var block=new RgbLightVolume(16,256,16);var sky=new RgbLightVolume(16,256,16);
        var beforeBlock=block.snapshot();var beforeSky=sky.snapshot();var baseline=full(id,1,beforeBlock,beforeSky);long sequence=1;
        for(int phase=0;phase<24;phase++) {
            for(int i=0;i<19;i++){block.set(rng.nextInt(65536),rng.nextInt(4096));sky.set(rng.nextInt(65536),rng.nextInt(4096));}
            var nextBlock=block.publish();var nextSky=sky.publish();var patch=delta(id,sequence+1,sequence,beforeBlock,beforeSky,nextBlock,nextSky);
            baseline=roundTrip(RegionFrameV4Codec.Packet.patch(patch)).delta().apply(baseline);sequence++;
            var expected=full(id,sequence,nextBlock,nextSky);
            assertEquals(FrameDigest.sha256(expected),FrameDigest.sha256(baseline));assertEquals(FrameDigest.skySha256(expected),FrameDigest.skySha256(baseline));
            beforeBlock=nextBlock;beforeSky=nextSky;
        }
        for(int cell=0;cell<65536;cell++){block.set(cell,0);sky.set(cell,0);}
        var patch=delta(id,sequence+1,sequence,beforeBlock,beforeSky,block.publish(),sky.publish());baseline=patch.apply(baseline);
        for(int cell=0;cell<65536;cell++){assertEquals(0,baseline.color(cell));assertEquals(0,baseline.skyColor(cell));}
    }
    @Test void equalValuesAfterOwnerRebuildDoNotNeedATransfer() {
        var id=UUID.randomUUID();var a=new RgbLightVolume(16,256,16);var b=new RgbLightVolume(16,256,16);a.set(100,0x123);b.set(100,0x123);
        assertNull(delta(id,2,1,a.publish(),null,b.publish(),null));
        var empty=new RgbLightVolume(16,256,16);b.set(100,0);assertNull(delta(id,2,1,empty.snapshot(),null,b.publish(),null));
    }
    @Test void missingBaselineRequestsResyncWithoutChangingVisibleData() {
        var id=UUID.randomUUID();var block=new RgbLightVolume(16,256,16);var before=block.snapshot();var old=full(id,1,before,null);
        block.set(0,0xf00);var changed=block.publish();var patch=delta(id,2,1,before,null,changed,null);
        var store=new ClientRegionStore();store.apply(RegionFrame.helloV4(id,1,0,PROFILE),0);store.apply(old,0);store.forgetColumn(4);var held=store.snapshot();
        assertEquals(ClientRegionStore.DeltaResult.RESYNC,store.applyDelta(patch,0));assertSame(held,store.snapshot());
        assertTrue(store.apply(full(id,3,changed,null),0));assertEquals(0xf00,store.snapshot().sample(-32,0,-16));
        assertEquals(ClientRegionStore.DeltaResult.IGNORED,store.applyDelta(patch,0));
        block.set(1,0x0f0);assertEquals(ClientRegionStore.DeltaResult.APPLIED,store.applyDelta(delta(id,4,3,changed,null,block.publish(),null),0));
    }
    @Test void wrongSlotBaseAfterOtherSlotsAdvanceStillResynchronizes() {
        var id=UUID.randomUUID();var block=new RgbLightVolume(16,256,16);var before=block.snapshot();var store=new ClientRegionStore();
        store.apply(RegionFrame.helloV4(id,1,0,PROFILE),0);store.apply(full(id,1,before,null),0);
        store.apply(RegionFrame.removeV4(id,1,5,0,7),0);block.set(0,0xf00);
        var wrong=delta(id,6,2,before,null,block.publish(),null);var held=store.snapshot();
        assertEquals(ClientRegionStore.DeltaResult.RESYNC,store.applyDelta(wrong,0));assertSame(held,store.snapshot());
        var correct=delta(id,7,1,before,null,block.snapshot(),null);assertEquals(ClientRegionStore.DeltaResult.APPLIED,store.applyDelta(correct,0));
    }
    @Test void movementAndOldWorldPacketsCannotPatchAReusedSlot() {
        var id=UUID.randomUUID();var block=new RgbLightVolume(16,256,16);var before=block.snapshot();block.set(0,0xf00);var patch=delta(id,4,1,before,null,block.publish(),null);
        var store=new ClientRegionStore();store.apply(RegionFrame.helloV4(id,1,0,PROFILE),0);store.apply(full(id,1,before,null),0);store.apply(RegionFrame.removeV4(id,1,2,0,4),0);
        var moved=new CapturedRgbRegion.Bounds(64,0,64,1,16,1);store.apply(RegionFrame.upsertV4(id,1,3,0,4,moved,new short[65536],null),0);
        assertEquals(ClientRegionStore.DeltaResult.RESYNC,store.applyDelta(patch,0));assertEquals(0,store.snapshot().sample(64,0,64));assertEquals(-1,store.snapshot().sample(-32,0,-16));
        store.invalidateWorld();store.apply(RegionFrame.helloV4(UUID.randomUUID(),2,-1,PROFILE),-1);
        assertEquals(ClientRegionStore.DeltaResult.IGNORED,store.applyDelta(patch,-1));
    }
    @Test void skyOnlyAndLanePresenceChangesAreExplicit() {
        var id=UUID.randomUUID();var sky=new RgbLightVolume(16,256,16);var before=sky.snapshot();sky.set(8192,0xabc);var next=sky.publish();
        var old=full(id,1,null,before);var patched=roundTrip(RegionFrameV4Codec.Packet.patch(delta(id,2,1,null,before,null,next))).delta().apply(old);
        assertFalse(patched.hasBlock());assertEquals(-1,patched.sample(-32,32,-16));assertEquals(0xabc,patched.skyColor(8192));
        assertThrows(IllegalArgumentException.class,()->delta(id,2,1,null,before,next,next));
        assertTrue(roundTrip(RegionFrameV4Codec.Packet.full(full(id,3,next,next))).frame().hasBlock());
    }
    @Test void allSectionsChangedShouldUseSmallerFullReplacement() {
        var id=UUID.randomUUID();var b=new RgbLightVolume(16,256,16);var s=new RgbLightVolume(16,256,16);var beforeB=b.snapshot();var beforeS=s.snapshot();
        for(int y=0;y<16;y++){b.set(y*4096,0xf00);s.set(y*4096,0x00f);}
        var patch=delta(id,2,1,beforeB,beforeS,b.publish(),s.publish());assertEquals(262212,patch.encodedBytes());
        var bytes=Unpooled.buffer();try{RegionFrameV4Codec.encode(RegionFrameV4Codec.Packet.full(full(id,2,b.snapshot(),s.snapshot())),bytes);assertEquals(262200,bytes.readableBytes());assertTrue(bytes.readableBytes()<patch.encodedBytes());}finally{bytes.release();}
    }
    @Test void malformedDeltaMasksLengthsValuesAndGenerationsAreRejected() {
        var id=UUID.randomUUID();var block=new RgbLightVolume(16,256,16);var before=block.snapshot();block.set(0,1);
        var packet=RegionFrameV4Codec.Packet.patch(delta(id,2,1,before,null,block.publish(),null));
        for(int scenario=0;scenario<7;scenario++) {
            var bytes=Unpooled.buffer();try{
                RegionFrameV4Codec.encode(packet,bytes);
                switch(scenario){case 0->bytes.setShort(38,3);case 1->bytes.setByte(52,2);case 2->bytes.setLong(56,2);case 3->bytes.setShort(66,1);case 4->bytes.setShort(68,0x1000);case 5->bytes.writeByte(0);default->bytes.writerIndex(bytes.writerIndex()-1);}
                assertThrows(IllegalArgumentException.class,()->RegionFrameV4Codec.decode(bytes));
            }finally{bytes.release();}
        }
    }
    @Test void newCapabilitiesDoNotChangeLegacyCodecs() {
        var peers=new PeerChannels<Object>();var connection=new Object();peers.registration(connection,"REGISTER",Set.of(PeerChannels.V2,PeerChannels.V3,PeerChannels.V4));assertTrue(peers.supportsV4(connection));
        peers.registration(connection,"UNREGISTER",Set.of(PeerChannels.V4));assertFalse(peers.supportsV4(connection));assertTrue(peers.supportsV3(connection));peers.disconnected(connection);assertFalse(peers.supportsV3(connection));
        var id=UUID.randomUUID();var frame=RegionFrame.upsertV3(id,1,1,0,4,BOUNDS,new short[65536],null);var bytes=Unpooled.buffer();
        try{RegionFrameV3Codec.encode(frame,bytes);assertThrows(IllegalArgumentException.class,()->RegionFrameV4Codec.decode(bytes));assertEquals(3,RegionFrameV3Codec.decode(bytes).protocol);}finally{bytes.release();}
        assertEquals(4,roundTrip(RegionFrameV4Codec.Packet.full(RegionFrame.helloV4(id,1,0,PROFILE))).frame().protocol);
        assertEquals(RegionFrame.Kind.REMOVE,roundTrip(RegionFrameV4Codec.Packet.full(RegionFrame.removeV4(id,1,2,0,4))).frame().kind);
    }
}

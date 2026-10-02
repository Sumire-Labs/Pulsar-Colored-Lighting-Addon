package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.world.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticStreamingTest {
    private static final String PROFILE="a".repeat(64);
    @Test void fullHeightV3RoundTripKeepsBothLanesAndNegativeCoordinates() {
        var bounds=new CapturedRgbRegion.Bounds(-32,0,16,1,16,1);short[] block=new short[65536],sky=new short[65536];
        for(int i=0;i<block.length;i++){block[i]=(short)(i&4095);sky[i]=(short)((i*7)&4095);}
        var frame=RegionFrame.upsertV3(UUID.randomUUID(),1,1,0,4,bounds,block,sky);var bytes=Unpooled.buffer();
        try {
            RegionFrameV3Codec.encode(frame,bytes);assertEquals(RegionFrameV3Codec.MAX_BYTES,bytes.readableBytes());var decoded=RegionFrameV3Codec.decode(bytes);
            assertEquals(3,decoded.protocol);assertEquals(bounds,decoded.bounds);
            for(int i=0;i<65536;i++){assertEquals(block[i],decoded.color(i));assertEquals(sky[i],decoded.skyColor(i));}
        }finally{bytes.release();}
    }
    @Test void movementRetiresOldSlotsBeforeReuseAndOldSnapshotsStayImmutable() {
        var id=UUID.randomUUID();var store=new ClientRegionStore();var slots=new AutomaticSlots();long sequence=0;
        store.apply(RegionFrame.helloV3(id,1,0,PROFILE),0);
        var first=new LinkedHashSet<>(AutomaticInterest.around(-3,-2,1));slots.update(first);
        for(long key:first){var b=new CapturedRgbRegion.Bounds((int)key*16,0,(int)(key>>32)*16,1,16,1);short[] data=new short[b.outputCells()];Arrays.fill(data,(short)0xf00);store.apply(RegionFrame.upsertV3(id,1,++sequence,0,slots.slot(key),b,data,null),0);}
        var old=store.snapshot();assertEquals(9,old.index().chunks());assertEquals(144,old.index().sections());
        var next=new LinkedHashSet<>(AutomaticInterest.around(2,-2,1));var removed=slots.update(next);assertEquals(9,removed.size());
        for(var item:removed)assertTrue(store.apply(RegionFrame.removeV3(id,1,++sequence,0,item.slot()),0));
        assertEquals(-1,store.snapshot().sample(-48,120,-32));assertEquals(0xf00,old.sample(-48,120,-32));
        for(long key:next){var b=new CapturedRgbRegion.Bounds((int)key*16,0,(int)(key>>32)*16,1,16,1);short[] data=new short[b.outputCells()];Arrays.fill(data,(short)0x00f);assertTrue(store.apply(RegionFrame.upsertV3(id,1,++sequence,0,slots.slot(key),b,data,null),0));}
        assertEquals(9,store.snapshot().regions().size());assertEquals(-1,store.snapshot().sample(-48,120,-32));assertEquals(0x00f,store.snapshot().sample(32,120,-32));
        var late=old.regions().values().iterator().next();assertFalse(store.apply(late,0));assertEquals(0x00f,store.snapshot().sample(32,120,-32));
    }
    @Test void protocolRegistrationAndWorldEpochsCannotResurrectRetiredData() {
        var peers=new PeerChannels<Object>();var connection=new Object();peers.registration(connection,"REGISTER",Set.of(PeerChannels.V2,PeerChannels.V3));assertTrue(peers.supportsV3(connection));
        peers.registration(connection,"UNREGISTER",Set.of(PeerChannels.V3));assertFalse(peers.supportsV3(connection));assertTrue(peers.supportsV2(connection));peers.disconnected(connection);assertFalse(peers.supportsV2(connection));
        var store=new ClientRegionStore();var old=UUID.randomUUID();store.apply(RegionFrame.helloV3(old,1,0,PROFILE),0);store.invalidateWorld();
        var next=UUID.randomUUID();assertTrue(store.apply(RegionFrame.helloV3(next,2,-1,PROFILE),-1));assertFalse(store.apply(RegionFrame.helloV3(old,1,0,PROFILE),-1));
        assertFalse(store.apply(RegionFrame.removeV3(old,1,20,-1,4),-1));
    }
    @Test void malformedV3HeadersAndLegacyFullHeightFramesAreRejected() {
        var b=new CapturedRgbRegion.Bounds(0,0,0,1,16,1);var id=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->RegionFrame.upsertV2(id,1,1,0,0,b,new short[65536],null));
        assertThrows(IllegalArgumentException.class,()->RegionFrame.upsertV3(id,1,1,0,256,b,new short[65536],null));
        var frame=RegionFrame.upsertV3(id,1,1,0,4,b,new short[65536],null);
        for(int scenario=0;scenario<3;scenario++){var bytes=Unpooled.buffer();try{RegionFrameV3Codec.encode(frame,bytes);switch(scenario){case 0->bytes.setShort(38,256);case 1->bytes.setByte(52,2);default->bytes.writerIndex(bytes.writerIndex()-1);}assertThrows(IllegalArgumentException.class,()->RegionFrameV3Codec.decode(bytes));}finally{bytes.release();}}
    }
}

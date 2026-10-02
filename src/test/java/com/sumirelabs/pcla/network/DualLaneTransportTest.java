package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DualLaneTransportTest {
    private static final UUID SESSION=new UUID(1,2);
    private static final String PROFILE="a".repeat(64);
    private static final CapturedRgbRegion.Bounds BOUNDS=new CapturedRgbRegion.Bounds(0,0,0,2,2,2);
    private static short[] values(int rgb) { short[] out=new short[BOUNDS.outputCells()]; Arrays.fill(out,(short)rgb); return out; }
    private static RegionFrame frame(long seq,short[] block,short[] sky) { return RegionFrame.upsertV2(SESSION,1,seq,0,0,BOUNDS,block,sky); }

    @Test void everyLaneCombinationRoundTripsWithBoundedPayloadAndOwnedArrays() {
        for(int mask=1;mask<=3;mask++) {
            short[] block=(mask&1)!=0 ? values(0xf12) : null,sky=(mask&2)!=0 ? values(0x3f4) : null;
            var frame=frame(1,block,sky); if(block!=null) block[0]=0; if(sky!=null) sky[0]=0;
            var wire=Unpooled.buffer();
            try {
                RegionFrameV2Codec.encode(frame,wire); assertTrue(wire.readableBytes()<=RegionFrameV2Codec.MAX_BYTES);
                var decoded=RegionFrameV2Codec.decode(wire); assertEquals(2,decoded.protocol);
                assertEquals(mask&1,decoded.hasBlock()?1:0); assertEquals(mask&2,decoded.hasSky()?2:0);
                assertEquals(block==null ? -1 : 0xf12,decoded.sample(0,0,0));
                assertEquals(sky==null ? -1 : 0x3f4,decoded.sampleSky(31,31,31));
                assertEquals(-1,decoded.sampleSky(32,0,0));
            } finally { wire.release(); }
        }
    }

    @Test void blockAndSkyReplaceTogetherAndDisabledLanesCannotRetainOldValues() {
        var store=new ClientRegionStore(); store.apply(RegionFrame.helloV2(SESSION,1,0,PROFILE),0);
        assertTrue(store.apply(frame(1,values(0xf00),values(0x0f0)),0)); var old=store.snapshot();
        assertTrue(store.apply(frame(2,values(0x00f),values(0xfff)),0));
        assertEquals(0x00f,store.snapshot().sample(0,0,0)); assertEquals(0xfff,store.snapshot().sampleSky(0,0,0));
        assertEquals(0xf00,old.sample(0,0,0)); assertEquals(0x0f0,old.sampleSky(0,0,0));
        store.apply(frame(3,null,values(0xabc)),0); assertEquals(-1,store.snapshot().sample(0,0,0));
        assertTrue(store.apply(RegionFrame.removeV2(SESSION,1,4,0,0),0)); assertEquals(-1,store.snapshot().sampleSky(0,0,0));
    }

    @Test void overlappingRegionsDoNotMixLanesFromDifferentWinningSlots() {
        var store=new ClientRegionStore(); store.apply(RegionFrame.helloV2(SESSION,1,0,PROFILE),0);
        store.apply(frame(1,null,values(0x0f0)),0);
        store.apply(RegionFrame.upsertV2(SESSION,1,2,0,1,BOUNDS,values(0xf00),values(0x00f)),0);
        assertEquals(-1,store.snapshot().sample(0,0,0)); assertEquals(0x0f0,store.snapshot().sampleSky(0,0,0));
    }

    @Test void protocolTransitionRejectsLateLegacyMessagesAndKeepsTheirFallbackUsable() {
        var store=new ClientRegionStore(); store.apply(RegionFrame.hello(SESSION,1,0,PROFILE),0);
        store.apply(RegionFrame.upsert(SESSION,1,1,0,0,BOUNDS,values(0xf00)),0);
        assertEquals(-1,store.snapshot().sampleSky(0,0,0));
        assertFalse(store.apply(frame(2,values(0x00f),values(0x0f0)),0));
        var newer=new UUID(3,4); assertTrue(store.apply(RegionFrame.helloV2(newer,2,0,PROFILE),0));
        assertFalse(store.apply(RegionFrame.upsert(SESSION,1,99,0,0,BOUNDS,values(0xf00)),0));
        assertTrue(store.apply(RegionFrame.upsertV2(newer,2,1,0,0,BOUNDS,null,values(0xfff)),0));
    }

    @Test void malformedAndWrongVersionPayloadsAreRejected() {
        var wire=Unpooled.buffer();
        try {
            RegionFrameV2Codec.encode(frame(1,values(0xf00),null),wire);
            assertThrows(IllegalArgumentException.class,()->RegionFrameCodec.decode(wire.duplicate()));
            int maskIndex=54; // Fixed 38-byte header, slot, 15-byte bounds.
            wire.setByte(maskIndex,0); assertThrows(IllegalArgumentException.class,()->RegionFrameV2Codec.decode(wire.duplicate()));
            wire.setByte(maskIndex,4); assertThrows(IllegalArgumentException.class,()->RegionFrameV2Codec.decode(wire.duplicate()));
            wire.setByte(maskIndex,1); wire.writerIndex(wire.writerIndex()-1);
            assertThrows(IllegalArgumentException.class,()->RegionFrameV2Codec.decode(wire.duplicate()));
            wire.clear(); RegionFrameCodec.encode(RegionFrame.hello(SESSION,1,0,PROFILE),wire);
            assertThrows(IllegalArgumentException.class,()->RegionFrameV2Codec.decode(wire.duplicate()));
        } finally { wire.release(); }
        assertThrows(IllegalArgumentException.class,()->frame(1,null,null));
        assertThrows(IllegalArgumentException.class,()->frame(1,values(0x1000),null));
    }

    @Test void peerRegistrationIsConnectionScopedAndUnrelatedChannelsCannotEnableV2() {
        var peers=new PeerChannels<Object>(); Object first=new Object(),second=new Object();
        peers.registration(first,"REGISTER",Set.of("other")); assertFalse(peers.supportsV2(first));
        peers.registration(first,"REGISTER",Set.of(PeerChannels.V2)); assertTrue(peers.supportsV2(first)); assertFalse(peers.supportsV2(second));
        peers.registration(first,"UNREGISTER",Set.of("other")); assertTrue(peers.supportsV2(first));
        peers.registration(first,"UNREGISTER",Set.of(PeerChannels.V2)); assertFalse(peers.supportsV2(first));
        peers.registration(first,"REGISTER",Set.of(PeerChannels.V2)); peers.disconnected(first); assertFalse(peers.supportsV2(first));
    }

    @Test void diagnosticsHashEachLaneIndependently() {
        var a=frame(1,values(0xf00),values(0x0f0)); var b=frame(2,values(0xf00),values(0x00f));
        assertEquals(FrameDigest.sha256(a),FrameDigest.sha256(b)); assertNotEquals(FrameDigest.skySha256(a),FrameDigest.skySha256(b));
        assertEquals("absent",FrameDigest.sha256(frame(3,null,values(0xfff))));
    }
}

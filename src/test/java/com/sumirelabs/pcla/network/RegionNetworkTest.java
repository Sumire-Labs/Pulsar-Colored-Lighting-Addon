package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import io.netty.buffer.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionNetworkTest {
    private static final UUID SESSION=UUID.randomUUID();
    private static final String PROFILE="a".repeat(64);
    private static final CapturedRgbRegion.Bounds BOUNDS=new CapturedRgbRegion.Bounds(-32,16,-16,2,2,2);
    private static RegionFrame field(long sequence,int slot,int color) {
        short[] data=new short[BOUNDS.outputCells()]; Arrays.fill(data,(short)color);
        return RegionFrame.upsert(SESSION,1,sequence,0,slot,BOUNDS,data);
    }
    @Test void fullEightSectionPayloadRoundTripsEveryPackedColorAndIsImmutable() {
        short[] data=new short[BOUNDS.outputCells()];
        for(int cell=0;cell<data.length;cell++) data[cell]=(short)(cell&4095);
        var frame=RegionFrame.upsert(SESSION,1,1,-1,3,BOUNDS,data);
        data[17]=0;
        assertEquals(17,frame.color(17));
        ByteBuf buffer=Unpooled.buffer();
        try {
            RegionFrameCodec.encode(frame,buffer);
            assertTrue(buffer.readableBytes()<RegionFrameCodec.MAX_BYTES);
            var copy=RegionFrameCodec.decode(buffer);
            assertEquals(frame.bounds,copy.bounds); assertEquals(3,copy.slot); assertEquals(-1,copy.dimension);
            for(int cell=0;cell<frame.cells();cell++) assertEquals(cell&4095,copy.color(cell));
            assertEquals(-1,copy.sample(-33,16,-16));
            assertEquals(17,copy.sample(-15,16,-16));
        } finally { buffer.release(); }
    }
    @Test void helloAndRemoveRoundTripAndMalformedPayloadsAreRejectedBeforeApplication() {
        for(var frame:List.of(RegionFrame.hello(SESSION,1,0,PROFILE),RegionFrame.remove(SESSION,1,2,0,3))) {
            ByteBuf buffer=Unpooled.buffer();
            try { RegionFrameCodec.encode(frame,buffer); assertEquals(frame.kind,RegionFrameCodec.decode(buffer).kind); }
            finally { buffer.release(); }
        }
        ByteBuf valid=Unpooled.buffer();
        try {
            RegionFrameCodec.encode(field(1,0,0xabc),valid);
            for(int length:new int[]{0,1,37,38,39,52,53,valid.readableBytes()-1}) {
                var slice=valid.slice(0,length);
                assertThrows(RuntimeException.class,()->RegionFrameCodec.decode(slice));
            }
            for(int offset:new int[]{0,1,38,51,52,53,54}) {
                ByteBuf corrupt=valid.copy();
                try { corrupt.setByte(offset,255); assertThrows(RuntimeException.class,()->RegionFrameCodec.decode(corrupt)); }
                finally { corrupt.release(); }
            }
            ByteBuf extra=valid.copy();
            try { extra.writeByte(0); assertThrows(RuntimeException.class,()->RegionFrameCodec.decode(extra)); }
            finally { extra.release(); }
        } finally { valid.release(); }
    }
    @Test void sessionsDimensionsOrderingAndWorldRetirementRejectStaleResults() {
        var store=new ClientRegionStore();
        assertFalse(store.apply(field(1,0,0xf00),0));
        var hello=RegionFrame.hello(SESSION,1,0,PROFILE);
        assertFalse(store.apply(hello,1)); assertTrue(store.apply(hello,0));
        assertTrue(store.apply(field(2,0,0xf00),0));
        var held=store.snapshot();
        assertFalse(store.apply(field(1,0,0x00f),0)); assertFalse(store.apply(field(2,0,0x00f),0));
        assertTrue(store.apply(hello,0)); assertSame(held,store.snapshot());
        assertFalse(store.apply(RegionFrame.remove(UUID.randomUUID(),1,3,0,0),0));
        assertTrue(store.apply(RegionFrame.remove(SESSION,1,3,0,0),0));
        assertEquals(-1,store.snapshot().sample(-32,16,-16));
        assertEquals(0xf00,held.sample(-32,16,-16));
        assertThrows(UnsupportedOperationException.class,()->held.regions().clear());
        store.invalidateWorld(); assertNull(store.snapshot());
        assertFalse(store.apply(hello,0)); assertFalse(store.apply(field(4,0,0xf00),0));
        UUID next=UUID.randomUUID();
        assertTrue(store.apply(RegionFrame.hello(next,2,-1,PROFILE),-1));
        assertFalse(store.apply(hello,0));
        store.clear(); assertTrue(store.apply(hello,0)); // A new physical connection has its own epoch namespace.
    }
    @Test void overlappingSlotsHaveDeterministicOwnershipAndInvalidHeadersCannotAllocateRegions() {
        var store=new ClientRegionStore(); store.apply(RegionFrame.hello(SESSION,1,0,PROFILE),0);
        store.apply(field(1,3,0x00f),0); store.apply(field(2,0,0xf00),0);
        assertEquals(0xf00,store.snapshot().sample(-32,16,-16));
        store.apply(RegionFrame.remove(SESSION,1,3,0,0),0);
        assertEquals(0x00f,store.snapshot().sample(-32,16,-16));
        assertThrows(IllegalArgumentException.class,()->field(4,4,0));
        assertThrows(IllegalArgumentException.class,()->field(4,1,0x1000));
        assertThrows(IllegalArgumentException.class,()->RegionFrame.hello(SESSION,0,0,PROFILE));
        assertThrows(IllegalArgumentException.class,()->RegionFrame.hello(SESSION,1,0,"bad"));
    }
}

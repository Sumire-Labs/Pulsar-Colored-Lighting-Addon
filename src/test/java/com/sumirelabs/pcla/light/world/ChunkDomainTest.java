package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.light.engine.*;
import com.sumirelabs.pcla.network.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkDomainTest {
    @Test void fullColumnOwnsEveryOutputCellWhileLegacyTransportStaysBounded() {
        var bounds=new CapturedRgbRegion.Bounds(-16,0,16,1,16,1);
        assertEquals(65536,bounds.outputCells()); assertEquals(605176,bounds.cells()); assertEquals(9,bounds.chunks().size());
        var seen=new BitSet(bounds.cells());
        for(int cell=0;cell<bounds.outputCells();cell++) {
            int input=bounds.interiorIndex(cell); assertFalse(seen.get(input)); seen.set(input);
            assertEquals(input,bounds.inputCell(-16+(cell&15),cell>>>8,16+(cell>>>4&15)));
        }
        assertEquals(65536,seen.cardinality());
        assertThrows(IllegalArgumentException.class,()->new RegionDirectory<>().replace(0,bounds));
        assertThrows(IllegalArgumentException.class,()->RegionFrame.upsertV2(UUID.randomUUID(),1,1,0,0,bounds,new short[65536],null));
        assertThrows(IllegalArgumentException.class,()->new CapturedRgbRegion.Bounds(0,0,0,1,17,1));
    }
    @Test void chunkOwnersHaveIndependentLifetimesBeyondFourLegacySlots() {
        var directory=new ChunkDomainDirectory<Object,String>(); Map<Long,Object> chunks=new HashMap<>();
        List<ChunkDomainDirectory.Entry<Object,String>> entries=new ArrayList<>();
        for(int x=0;x<5;x++) {
            var bounds=new CapturedRgbRegion.Bounds(x*16,0,0,1,16,1);
            for(long key:bounds.chunks()) chunks.putIfAbsent(key,new Object());
            entries.add(directory.watch(x,0,bounds));
        }
        chunks.forEach(directory::loaded); assertEquals(5,directory.entries().size());
        for(var entry:entries) assertTrue(entry.gate().publish(entry.gate().begin(),"ready"));
        directory.changedAt(8,254,8);
        assertNull(entries.get(0).gate().snapshot()); assertNull(entries.get(1).gate().snapshot());
        assertEquals("ready",entries.get(2).gate().snapshot());
        var old=entries.get(0); var ticket=old.gate().begin();
        assertSame(old,directory.watch(0,0,old.bounds()));
        assertTrue(directory.remove(0,0)); assertFalse(old.gate().publish(ticket,"late"));
        var next=directory.watch(0,0,old.bounds()); assertNotSame(old,next);
        var beforeReload=next.gate().begin();
        directory.loaded(RegionPublication.key(0,0),new Object());
        assertFalse(next.gate().publish(beforeReload,"old chunk"));
        directory.close(); assertNull(next.gate().begin()); assertTrue(directory.entries().isEmpty());
    }
    @Test void blockLightCannotEscapeThroughPhantomAirOutsideWorldHeight() {
        var b=new CapturedRgbRegion.Bounds(0,0,0); var input=new CapturedRgbRegion(b,false,0,0);
        input.refresh((cell,out)->{
            int x=b.x()-15+cell%b.inputX(),y=b.y()-15+cell/(b.inputX()*b.inputZ()),z=b.z()-15+cell/b.inputX()%b.inputZ();
            out.source=y!=0?0xfff:x==0&&z==8?0xf00:0; out.blockedFaces=x==1?63:0; out.contextual=false;
            Arrays.fill(out.incoming,x==1?0xfff:0);
        },new BitSet(),true);
        new RgbBlockPropagator(input).relight();
        assertEquals(0xf00,input.light(b.inputCell(0,0,8)));
        assertEquals(0,input.light(b.inputCell(2,0,8)),"cannot route around the wall above/below the world");
        assertEquals(0,input.light(b.inputCell(0,-1,8))); assertEquals(0,input.light(b.inputCell(0,1,8)));
    }
    @Test void skyStartsJustAboveTheRealWorldAndDoesNotCrossExtraPaddingAir() {
        var b=new CapturedRgbRegion.Bounds(0,0,0,1,16,1); var input=new CapturedRgbRegion(b,false,0,255);
        input.refresh((cell,out)->{
            int y=b.y()-15+cell/(b.inputX()*b.inputZ());
            out.source=0; out.blockedFaces=y>255?8:0; out.contextual=false; Arrays.fill(out.incoming,0x001);
        },new BitSet(),true);
        var sky=new CapturedSkyRegion(input,0,255);
        sky.refreshColumns((column,out)->{out.mask=0xfff;out.contextual=false;},new BitSet(),true);
        new RgbSkyPropagator(sky).relight();
        assertEquals(0xfff,sky.light(b.inputCell(8,256,8)));
        assertEquals(0xffe,sky.light(b.inputCell(8,255,8)),"only the first real block absorbs blue");
        assertEquals(0xff0,sky.light(b.inputCell(8,241,8)));
        assertEquals(0,sky.light(b.inputCell(8,257,8))); assertEquals(0,sky.light(b.inputCell(8,-1,8)));
        var old=sky.interior();
        var columns=sky.refreshColumns((column,out)->{out.mask=0xf00;out.contextual=false;},new BitSet(),true);
        new RgbSkyPropagator(sky).update(sky.changedRoots(new int[0],columns.changedColumns()));
        assertEquals(0xf00,sky.light(b.inputCell(8,255,8)));
        assertEquals(0xf00,sky.light(b.inputCell(8,0,8)));
        assertEquals(0xffe,old.get(8+16*(8+16*255)),"retained output remains immutable");
    }
    @Test void tallWireHeadersAreRejectedBeforeReadingTheirPayloads() {
        for(int protocol:new int[]{1,2}) {
            var bytes=io.netty.buffer.Unpooled.buffer();
            try {
                bytes.writeByte(protocol).writeByte(1).writeLong(0).writeLong(1).writeLong(1).writeLong(1).writeInt(0);
                bytes.writeByte(0).writeInt(0).writeInt(0).writeInt(0).writeByte(1).writeByte(16).writeByte(1);
                if(protocol==2) bytes.writeByte(1);
                assertThrows(IllegalArgumentException.class,()->{if(protocol==1) RegionFrameCodec.decode(bytes);else RegionFrameV2Codec.decode(bytes);});
            } finally {bytes.release();}
        }
    }
}

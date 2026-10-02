package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.storage.RgbLightVolume;
import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BulkWireEncodingTest {
    static byte[] take(ByteBuf out) {var result=new byte[out.readableBytes()];out.readBytes(result);return result;}
    static void header(ByteBuf out,int protocol,int kind,UUID session,long epoch,long sequence,int dimension,int slot,CapturedRgbRegion.Bounds bounds,int lanes) {
        out.writeByte(protocol);out.writeByte(kind);out.writeLong(session.getMostSignificantBits());out.writeLong(session.getLeastSignificantBits());
        out.writeLong(epoch);out.writeLong(sequence);out.writeInt(dimension);out.writeShort(slot);
        out.writeInt(bounds.x());out.writeInt(bounds.y());out.writeInt(bounds.z());out.writeByte(bounds.sectionsX());out.writeByte(bounds.sectionsY());out.writeByte(bounds.sectionsZ());out.writeByte(lanes);
    }
    static byte[] scalar(RegionFrame frame) {
        var out=Unpooled.buffer();try {
            header(out,frame.protocol,1,frame.session,frame.epoch,frame.sequence,frame.dimension,frame.slot,frame.bounds,(frame.hasBlock()?1:0)|(frame.hasSky()?2:0));
            if(frame.hasBlock())for(int cell=0;cell<frame.cells();cell++)out.writeShort(frame.color(cell));
            if(frame.hasSky())for(int cell=0;cell<frame.cells();cell++)out.writeShort(frame.skyColor(cell));
            return take(out);
        }finally {out.release();}
    }
    static byte[] scalar(SectionDelta delta) {
        var out=Unpooled.buffer();try {
            header(out,4,3,delta.session,delta.epoch,delta.sequence,delta.dimension,delta.slot,delta.bounds,delta.lanes);
            out.writeLong(delta.baseSequence);out.writeShort(delta.blockMask);out.writeShort(delta.skyMask);
            for(boolean sky:new boolean[]{false,true})for(int y=0;y<delta.bounds.sectionsY();y++)if(((sky?delta.skyMask:delta.blockMask)&(1<<y))!=0)
                for(int cell=0;cell<4096;cell++)out.writeShort(delta.value(sky,y,cell));
            return take(out);
        }finally {out.release();}
    }
    @Test void fullBulkIsByteExactForSnapshotsOwnedSlicesPresentZeroAndAbsentLanes() {
        var rng=new Random(471);var block=new RgbLightVolume.WordColumnVolume(48);var sky=new RgbLightVolume(16,48,16);
        // One zero section, one dense varied section, one uniform section.
        for(int cell=4096;cell<8192;cell++){block.setRgb12(cell,rng.nextInt(4096));sky.set(cell,rng.nextInt(4096));}
        for(int cell=8192;cell<12288;cell++){block.setRgb12(cell,0x123);sky.set(cell,0xabc);}
        var b=block.publish();var s=sky.publish();
        for(int protocol=3;protocol<=4;protocol++)for(int lanes=1;lanes<=3;lanes++) {
            var br=(lanes&1)==0?null:b;var sr=(lanes&2)==0?null:s;
            var shared=RegionFrame.upsertSnapshot(protocol,SnapshotWireTest.ID,1,1,0,4,SnapshotWireTest.B,br,sr);
            var owned=SnapshotWireTest.owned(protocol,1,br,sr);
            assertArrayEquals(scalar(owned),SnapshotWireTest.bytes(shared));
            assertArrayEquals(scalar(owned),SnapshotWireTest.bytes(owned));
        }
    }
    @Test void widerLegacyFramesKeepGlobalRowsInsteadOfConcatenatingSections() {
        var bounds=new CapturedRgbRegion.Bounds(-32,-16,64,2,2,2);var rng=new Random(472);
        var block=new short[bounds.outputCells()];var sky=new short[block.length];
        for(int i=0;i<block.length;i++){block[i]=(short)rng.nextInt(4096);sky[i]=(short)rng.nextInt(4096);}
        for(int protocol=3;protocol<=4;protocol++) {
            var frame=protocol==3?RegionFrame.upsertV3(SnapshotWireTest.ID,1,1,0,0,bounds,block,sky):RegionFrame.upsertV4(SnapshotWireTest.ID,1,1,0,0,bounds,block,sky);
            assertArrayEquals(scalar(frame),SnapshotWireTest.bytes(frame));
        }
    }
    @Test void snapshotAndDefensiveDeltaBulkKeepChangedZeroAndLaneOrder() {
        var b=new RgbLightVolume.WordColumnVolume(48);var s=new RgbLightVolume(16,48,16);
        for(int cell=0;cell<4096;cell++){b.setRgb12(cell,0xabc);s.set(cell+4096,0x123);}
        var ob=b.publish();var os=s.publish();
        for(int cell=0;cell<4096;cell++){b.setRgb12(cell,0);s.set(cell+4096,0x456);}
        var nb=b.publish();var ns=s.publish();
        var d=SectionDelta.between(SnapshotWireTest.ID,1,2,1,0,4,SnapshotWireTest.B,ob,os,nb,ns);
        assertEquals(1,d.blockMask);assertEquals(2,d.skyMask);assertArrayEquals(scalar(d),SnapshotWireTest.bytes(d));
        short[][] ba=new short[3][],sa=new short[3][];ba[0]=new short[4096];sa[1]=new short[4096];Arrays.fill(sa[1],(short)0x456);
        var owned=new SectionDelta(SnapshotWireTest.ID,1,2,1,0,4,SnapshotWireTest.B,3,ba,sa);
        assertArrayEquals(scalar(owned),SnapshotWireTest.bytes(owned));assertArrayEquals(SnapshotWireTest.bytes(owned),SnapshotWireTest.bytes(d));
    }
}

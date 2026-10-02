package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkLightIndexTest {
    private static final UUID SESSION=UUID.fromString("a28013ac-52c6-4a36-b092-61d4bb94dfea");
    private static RegionFrame frame(int slot,CapturedRgbRegion.Bounds bounds,boolean block,boolean sky) {
        short[] a=new short[bounds.outputCells()],b=new short[a.length];
        for(int i=0;i<a.length;i++) { a[i]=(short)((i*17+slot*19)&4095); b[i]=(short)((i*31+slot*7+11)&4095); }
        return RegionFrame.upsertV2(SESSION,1,slot+1,0,slot,bounds,block?a:null,sky?b:null);
    }
    @Test void indexedSamplingMatchesRegionPriorityAcrossOverlapsAndAllBoundaries() {
        Map<Integer,RegionFrame> frames=new TreeMap<>();
        frames.put(3,frame(3,new CapturedRgbRegion.Bounds(16,-16,0,2,2,2),true,true));
        frames.put(2,frame(2,new CapturedRgbRegion.Bounds(-16,0,0,2,2,2),true,false));
        frames.put(1,frame(1,new CapturedRgbRegion.Bounds(-32,-16,-16,2,2,2),true,true));
        frames.put(0,frame(0,new CapturedRgbRegion.Bounds(-16,0,0),false,true));
        var index=new ChunkLightIndex(frames);
        // Cache the linear oracle's order outside the coordinate loop.
        var ordered=List.copyOf(frames.values());
        for(int y=-18;y<=33;y++) for(int z=-18;z<=33;z++) for(int x=-34;x<=49;x++) {
            int block=-1,sky=-1;
            for(var f:ordered) if(f.contains(x,y,z)) { block=f.sample(x,y,z); sky=f.sampleSky(x,y,z); break; }
            assertEquals(block,index.block(x,y,z)); assertEquals(sky,index.sky(x,y,z));
        }
        assertEquals(-1,index.block(-16,0,0),"missing BLOCK lane in the winning slot must not merge a lower slot");
        assertNotEquals(-1,index.sky(-16,0,0));
    }
    @Test void removingTheOwnerRevealsTheNextRegionWithoutChangingAnOlderSnapshot() {
        var b=new CapturedRgbRegion.Bounds(-16,-16,-16); var store=new ClientRegionStore();
        assertTrue(store.apply(RegionFrame.helloV2(SESSION,1,0,"0".repeat(64)),0));
        var lower=frame(1,b,true,true); assertTrue(store.apply(lower,0));
        var upper=RegionFrame.upsertV2(SESSION,1,3,0,0,b,null,new short[4096]); assertTrue(store.apply(upper,0));
        var held=store.snapshot(); assertEquals(1,held.index().chunks()); assertEquals(1,held.index().sections());
        assertEquals(-1,held.sample(-1,-1,-1)); assertEquals(0,held.sampleSky(-1,-1,-1));
        assertTrue(store.apply(RegionFrame.removeV2(SESSION,1,4,0,0),0));
        assertEquals(lower.sample(-1,-1,-1),store.snapshot().sample(-1,-1,-1));
        assertEquals(lower.sampleSky(-1,-1,-1),store.snapshot().sampleSky(-1,-1,-1));
        assertEquals(-1,held.sample(-1,-1,-1)); assertEquals(0,held.sampleSky(-1,-1,-1));
        store.invalidateWorld(); assertNull(store.snapshot()); assertEquals(0,held.sampleSky(-1,-1,-1));
    }
    @Test void sectionViewsKeepFullFrameLayoutAndDoNotBorrowMutableCallerArrays() {
        var bounds=new CapturedRgbRegion.Bounds(-32,16,-16,2,2,2);
        short[] source=new short[bounds.outputCells()]; for(int i=0;i<source.length;i++) source[i]=(short)(i&4095);
        var f=RegionFrame.upsertV2(SESSION,1,1,0,0,bounds,source,null); Arrays.fill(source,(short)0xfff);
        for(int sy=0;sy<2;sy++) for(int sz=0;sz<2;sz++) for(int sx=0;sx<2;sx++) {
            var section=f.section(sx,sy,sz);
            for(int cell=0;cell<4096;cell++) {
                int x=bounds.x()+sx*16+(cell&15),z=bounds.z()+sz*16+(cell>>>4&15),y=bounds.y()+sy*16+(cell>>>8);
                assertEquals(f.sample(x,y,z),section.block(cell)); assertEquals(-1,section.sky(cell));
            }
            assertThrows(IndexOutOfBoundsException.class,()->section.block(4096));
        }
        assertEquals(0,f.section(0,0,0).block(0));
        assertThrows(IndexOutOfBoundsException.class,()->f.section(-1,0,0));
    }
    @Test void coordinateExtremesAndSnapshotConstructorsRetainTheOldContract() {
        var b=new CapturedRgbRegion.Bounds(Integer.MAX_VALUE-31,Integer.MIN_VALUE+16,0);
        var f=frame(0,b,true,true); Map<Integer,RegionFrame> frames=new HashMap<>(); frames.put(0,f);
        var a=new ClientRegionStore.Snapshot(SESSION,1,1,0,"profile",frames,2);
        var same=new ClientRegionStore.Snapshot(SESSION,1,1,0,"profile",frames,2);
        assertEquals(a,same); assertEquals(a.hashCode(),same.hashCode());
        assertEquals(f.sample(b.x(),b.y(),0),a.sample(b.x(),b.y(),0));
        assertEquals(-1,a.sample(Integer.MAX_VALUE,b.y(),0)); assertEquals(-1,a.sample(b.x(),Integer.MIN_VALUE,0));
        frames.clear(); assertEquals(1,a.regions().size()); assertEquals(1,a.index().sections());
        assertThrows(UnsupportedOperationException.class,()->a.regions().clear());
        assertEquals(1,new ClientRegionStore.Snapshot(SESSION,1,1,0,"profile",Map.of()).protocol());
    }
}

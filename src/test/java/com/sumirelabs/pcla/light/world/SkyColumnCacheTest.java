package com.sumirelabs.pcla.light.world;

import org.junit.jupiter.api.Test;
import java.util.BitSet;
import static org.junit.jupiter.api.Assertions.*;

class SkyColumnCacheTest {
    @Test void ordinaryEditsReadOnlyDirtyAndPreviouslyContextualColumns() {
        var cache=new SkyColumnCache(5); int[] masks={0xfff,0xfff,0xfff,0xfff,0xfff}; boolean[] contextual={false,false,true,false,false};
        int[] reads=new int[5];
        SkyColumnCache.Reader reader=(column,out)-> { reads[column]++; out.mask=masks[column]; out.contextual=contextual[column]; };
        assertThrows(IllegalStateException.class,()->cache.mask(0));
        assertEquals(5,cache.refresh(reader,new BitSet(),false).sampledColumns());
        masks[1]=0xf0f; var dirty=new BitSet(); dirty.set(1);
        var changed=cache.refresh(reader,dirty,false);
        assertEquals(2,changed.sampledColumns()); assertArrayEquals(new int[]{1},changed.changedColumns());
        assertArrayEquals(new int[]{1,2,2,1,1},reads); assertEquals(0xf0f,cache.mask(1));
        masks[2]=0xff0; assertArrayEquals(new int[]{2},cache.refresh(reader,new BitSet(),false).changedColumns());
        contextual[2]=false; assertEquals(1,cache.refresh(reader,new BitSet(),false).sampledColumns());
        assertEquals(0,cache.refresh(reader,new BitSet(),false).sampledColumns());
    }

    @Test void aFailedColumnReadHidesAllMasksAndForcesCompleteRecovery() {
        var cache=new SkyColumnCache(4);
        SkyColumnCache.Reader white=(column,out)-> { out.mask=0xfff; out.contextual=false; };
        cache.refresh(white,new BitSet(),true);
        assertThrows(IllegalStateException.class,()->cache.refresh((column,out)-> {
            if(column==2) throw new IllegalStateException("upper chunk unavailable");
            out.mask=0xf00; out.contextual=false;
        },new BitSet(),true));
        assertThrows(IllegalStateException.class,()->cache.mask(0));
        assertEquals(4,cache.refresh(white,new BitSet(),false).sampledColumns());
        for(int c=0;c<4;c++) assertEquals(0xfff,cache.mask(c));
    }

    @Test void invalidDirtyIndicesDoNotDestroyAReadyCacheButInvalidReaderMasksDo() {
        var cache=new SkyColumnCache(2);
        SkyColumnCache.Reader white=(column,out)->out.mask=0xfff;
        cache.refresh(white,new BitSet(),true);
        var invalid=new BitSet(); invalid.set(2);
        assertThrows(IndexOutOfBoundsException.class,()->cache.refresh(white,invalid,false));
        assertEquals(0xfff,cache.mask(0));
        assertThrows(IllegalArgumentException.class,()->cache.refresh((column,out)->out.mask=0xeee,new BitSet(),true));
        assertThrows(IllegalStateException.class,()->cache.mask(0));
    }
}

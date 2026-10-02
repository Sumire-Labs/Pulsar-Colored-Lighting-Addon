package com.sumirelabs.pcla.light.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpticalCheckpointTest {
    private static final CapturedRgbRegion.Bounds B=new CapturedRgbRegion.Bounds(-16,0,16);
    private static void air(CapturedRgbRegion.Input v){v.source=0;v.blockedFaces=0;v.contextual=false;Arrays.fill(v.incoming,0);}
    private record Fixture(OpticalCheckpoint checkpoint,CapturedRgbRegion region,RegionPublication<Object,String> gate){}
    private Fixture fixture(int contextualCell) {
        var chunks=new HashMap<Long,Object>();for(long key:B.chunks())chunks.put(key,new Object());
        var store=new SharedOpticalInputs<Object>();
        var view=store.capture(List.of(SharedOpticalInputs.Request.full(B,chunks,null)),(chunk,x,y,z,v)->{
            air(v);v.contextual=B.inputCell(x,y,z)==contextualCell;
        }).getFirst();
        var region=new CapturedRgbRegion(B,true);region.refreshShared(view,B.cells());
        var gate=new RegionPublication<Object,String>();gate.watch(chunks.keySet());chunks.forEach(gate::loaded);gate.publish(gate.begin(),"light");
        return new Fixture(region.checkpoint(),region,gate);
    }
    private RegionPublication.Ticket<Object> dirty(Fixture f,int cell) {
        f.gate.changedAt(B.chunks().iterator().next(),cell);return f.gate.begin();
    }
    @Test void unchangedInputsCanBeCheckedWithoutAWorkingLightHalo() {
        var f=fixture(-1);var ticket=dirty(f,B.inputCell(-8,8,24));
        var check=f.checkpoint.check(ticket,cellReader(-1));
        assertThrows(IllegalStateException.class,check::unchanged);
        while(!check.advance(1)){}
        assertTrue(check.unchanged());assertEquals(7,check.reads());
        assertTrue(f.gate.publish(ticket,"same light roots"));
        assertEquals("same light roots",f.gate.snapshot());
    }
    private static CapturedRgbRegion.Reader cellReader(int contextual){return (cell,v)->{air(v);v.contextual=cell==contextual;};}
    @Test void everyOpticalFieldAndContextualFlagRejectReuse() {
        var f=fixture(-1);int cell=B.inputCell(-8,8,24);var ticket=dirty(f,cell);
        for(int field=0;field<9;field++) {
            int changedField=field;
            var check=f.checkpoint.check(ticket,(at,v)->{
                air(v);if(at!=cell)return;
                if(changedField==0)v.source=0x10f;
                else if(changedField==1)v.blockedFaces=8;
                else if(changedField==2)v.contextual=true;
                else v.incoming[changedField-3]=0x101;
            });
            while(!check.advance(2)){}
            assertFalse(check.unchanged(),"field "+field);
        }
    }
    @Test void contextualCellsAreRecheckedEvenAwayFromTheNotification() {
        int contextual=B.inputCell(-20,-5,10);var f=fixture(contextual);
        var ticket=dirty(f,B.inputCell(-8,8,24));
        var check=f.checkpoint.check(ticket,(cell,v)->{air(v);v.contextual=cell==contextual;if(v.contextual)v.source=0x00f;});
        while(!check.advance(1)){}
        assertFalse(check.unchanged());
    }
    @Test void boundaryPlansMatchTheProductionIncrementalCaptureAndObeyBudgets() {
        var f=fixture(9);
        for(int cell:new int[]{0,B.inputX()-1,B.inputX()*B.inputZ()-1,B.cells()-1,B.inputCell(-8,8,24)}) {
            var ticket=dirty(f,cell);var actual=new BitSet();
            var check=f.checkpoint.check(ticket,(at,v)->{actual.set(at);air(v);v.contextual=at==9;});
            int previous=0;
            while(!check.advance(2)){assertTrue(check.reads()-previous<=2);previous=check.reads();}
            assertTrue(check.unchanged());assertArrayEquals(f.region.planShared(ticket,false),actual.stream().toArray());
            f.gate.publish(ticket,"done");
        }
    }
    @Test void concurrentInvalidationStillRejectsReuseAndFullInvalidationsCannotBypassCapture() {
        var f=fixture(-1);var ticket=dirty(f,0);var check=f.checkpoint.check(ticket,cellReader(-1));
        check.advance(1);dirty(f,100);
        while(!check.advance(1)){}
        assertTrue(check.unchanged());assertFalse(f.gate.publish(ticket,"stale"));assertNull(f.gate.snapshot());
        f.gate.changed(B.chunks().iterator().next());
        assertThrows(IllegalArgumentException.class,()->f.checkpoint.check(f.gate.begin(),cellReader(-1)));
    }
}

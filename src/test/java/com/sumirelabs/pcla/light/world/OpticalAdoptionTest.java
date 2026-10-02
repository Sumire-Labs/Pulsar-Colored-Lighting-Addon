package com.sumirelabs.pcla.light.world;

import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpticalAdoptionTest {
    private static Map<Long,Object> chunks(CapturedRgbRegion.Bounds b) {var map=new HashMap<Long,Object>();b.chunks().forEach(key->map.put(key,new Object()));return map;}
    private static SharedOpticalInputs.Request<Object> full(CapturedRgbRegion.Bounds b,Map<Long,Object> tokens) {return new SharedOpticalInputs.Request<>(b,tokens,IntStream.range(0,b.cells()).toArray());}
    private static void value(CapturedRgbRegion.Input out,int source,boolean dynamic) {out.source=source;out.contextual=dynamic;out.blockedFaces=0;Arrays.fill(out.incoming,0);}
    @Test void partialAdoptionKeepsPriorInputAndContextualMaskUntilItCommits() {
        var b=new CapturedRgbRegion.Bounds(0,0,0);var tokens=chunks(b);var store=new SharedOpticalInputs<Object>();var region=new CapturedRgbRegion(b,true);
        var old=store.capture(List.of(full(b,tokens)),(c,x,y,z,out)->value(out,0,false)).getFirst();region.refreshShared(old,b.cells());
        var next=store.capture(List.of(full(b,tokens)),(c,x,y,z,out)->value(out,0xf00,true)).getFirst();
        var task=region.beginRefreshShared(next,b.cells());assertFalse(task.advance(1));
        assertSame(old,region.sharedInputs());assertEquals(0,region.emission(0));assertThrows(IllegalStateException.class,task::result);
        assertThrows(IllegalStateException.class,()->region.planShared(new BitSet(),false));
        task.cancel();assertSame(old,region.sharedInputs());assertEquals(0,region.planShared(new BitSet(),false).length);
        var resumed=region.beginRefreshShared(next,b.cells());while(!resumed.advance(17)){};
        assertEquals(b.cells(),resumed.result().changedCells().length);assertEquals(0,resumed.result().opacityChangedCells().length);
        assertEquals(b.cells(),region.planShared(new BitSet(),false).length);
        for(int cell=0;cell<b.cells();cell++){assertEquals(0xf00,region.emission(cell));assertEquals(0,old.emission(cell));}
    }
    @Test void slicedAdoptionProducesTheSameOrderedRootsAsSynchronousAdoption() {
        var b=new CapturedRgbRegion.Bounds(-16,0,-16);var tokens=chunks(b);var store=new SharedOpticalInputs<Object>();
        var before=store.capture(List.of(full(b,tokens)),(c,x,y,z,out)->value(out,Math.floorMod(x+y+z,4096),(x&7)==0)).getFirst();
        var after=store.capture(List.of(full(b,tokens)),(c,x,y,z,out)->{value(out,Math.floorMod(x*7+y*31+z,4096),(z&7)==0);out.incoming[2]=x==0?0xfff:0;}).getFirst();
        var sync=new CapturedRgbRegion(b,true);sync.refreshShared(before,b.cells());var expected=sync.refreshShared(after,b.cells());
        var sliced=new CapturedRgbRegion(b,true);sliced.refreshShared(before,b.cells());var task=sliced.beginRefreshShared(after,b.cells());while(!task.advance(1)){};
        assertArrayEquals(expected.changedCells(),task.result().changedCells());assertArrayEquals(expected.opacityChangedCells(),task.result().opacityChangedCells());
        assertArrayEquals(sync.planShared(new BitSet(),false),sliced.planShared(new BitSet(),false));
        for(int cell=0;cell<b.cells();cell++){assertEquals(sync.emission(cell),sliced.emission(cell));for(int d=0;d<6;d++)assertEquals(sync.incomingAbsorption(cell,d),sliced.incomingAbsorption(cell,d));}
    }
    @Test void differenceCallbacksAreBudgetedAndFailureCannotBeResumedAsSuccess() {
        var b=new CapturedRgbRegion.Bounds(0,0,0);var tokens=chunks(b);var store=new SharedOpticalInputs<Object>();
        var view=store.capture(List.of(full(b,tokens)),(c,x,y,z,out)->value(out,0xabc,false)).getFirst();int[] callbacks={0};
        var diff=view.beginDifferences(null,(cell,lighting,opacity,dynamic)->callbacks[0]++);
        assertFalse(diff.advance(1));assertTrue(callbacks[0]<=1);int before=callbacks[0];assertFalse(diff.advance(7));assertTrue(callbacks[0]-before<=7);
        var failed=view.beginDifferences(null,(cell,lighting,opacity,dynamic)->{throw new IllegalStateException("callback failed");});
        assertThrows(IllegalStateException.class,()->failed.advance(4096));assertThrows(IllegalStateException.class,()->failed.advance(4096));
        diff.cancel();assertThrows(IllegalStateException.class,()->diff.advance(1));
    }
}

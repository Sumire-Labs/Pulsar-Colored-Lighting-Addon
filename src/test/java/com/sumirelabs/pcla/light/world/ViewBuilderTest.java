package com.sumirelabs.pcla.light.world;

import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ViewBuilderTest {
    /** Test setup only: force a cold shared index without changing retained view arrays. */
    private static Map<?,?> coldIndices() throws Exception {
        var field=SharedOpticalInputs.View.class.getDeclaredField("INDICES");field.setAccessible(true);
        var cache=(Map<?,?>)field.get(null);cache.clear();return cache;
    }
    private static Map<Long,Object> chunks(CapturedRgbRegion.Bounds b){var map=new HashMap<Long,Object>();b.chunks().forEach(key->map.put(key,new Object()));return map;}
    private static SharedOpticalInputs.Request<Object> full(CapturedRgbRegion.Bounds b){return new SharedOpticalInputs.Request<>(b,chunks(b),IntStream.range(0,b.cells()).toArray());}
    private static void read(int x,int y,int z,CapturedRgbRegion.Input out){out.source=Math.floorMod(x*31+y*17+z,4096);out.contextual=false;out.blockedFaces=0;Arrays.fill(out.incoming,0);}
    private static void verify(SharedOpticalInputs.View view) {
        var b=view.bounds();for(int cell=0;cell<b.cells();cell++) {
            int x=b.x()-15+cell%b.inputX(),z=b.z()-15+cell/b.inputX()%b.inputZ(),y=b.y()-15+cell/(b.inputX()*b.inputZ());
            assertEquals(Math.floorMod(x*31+y*17+z,4096),view.emission(cell));
        }
    }
    @Test void cancelledPartialIndexNeverBecomesVisibleInTheSharedCache() throws Exception {
        var cache=coldIndices();var b=new CapturedRgbRegion.Bounds(-16,32,-16,1,5,1);var store=new SharedOpticalInputs<Object>();
        var task=store.beginCapture(List.of(full(b)),(c,x,y,z,out)->read(x,y,z,out));
        assertFalse(task.advance(b.cells()*2+7));assertEquals(7,task.indexBuildCells());assertEquals("VIEWS_INDEX",task.stage());
        assertTrue(cache.isEmpty());assertEquals(0,store.stats().sections());task.cancel();assertTrue(cache.isEmpty());
        var next=store.beginCapture(List.of(full(b)),(c,x,y,z,out)->read(x,y,z,out));while(!next.advance(1024)){};
        assertEquals(b.cells(),next.indexBuildCells());assertTrue(next.viewValidationSteps()>0);verify(next.result().getFirst());
    }
    @Test void anotherWorldCanFinishTheSameShapeWhileTheFirstBuilderIsPaused() throws Exception {
        coldIndices();var a=new CapturedRgbRegion.Bounds(-16,0,-16,1,5,1);var b=new CapturedRgbRegion.Bounds(32,-32,48,1,5,1);
        var first=new SharedOpticalInputs<Object>();var second=new SharedOpticalInputs<Object>();
        var paused=first.beginCapture(List.of(full(a)),(c,x,y,z,out)->read(x,y,z,out));paused.advance(a.cells()*2+11);
        assertEquals(11,paused.indexBuildCells());
        var other=second.capture(List.of(full(b)),(c,x,y,z,out)->read(x,y,z,out)).getFirst();verify(other);
        while(!paused.complete()) {long before=paused.indexBuildCells();paused.advance(17);assertTrue(paused.indexBuildCells()-before<=17);}
        verify(paused.result().getFirst());verify(other);
    }
    @Test void coverageValidationAlsoYieldsBeforeAViewCanBeRetrieved() throws Exception {
        coldIndices();var b=new CapturedRgbRegion.Bounds(0,0,0);var store=new SharedOpticalInputs<Object>();
        var task=store.beginCapture(List.of(full(b)),(c,x,y,z,out)->read(x,y,z,out));task.advance(b.cells()*3+1);
        assertEquals("VIEWS_VALIDATE",task.stage());assertFalse(task.complete());assertThrows(IllegalStateException.class,task::result);
        long before=task.viewValidationSteps();assertFalse(task.advance(3));assertEquals(3,task.viewValidationSteps()-before);
        while(!task.advance(64)){};verify(task.result().getFirst());
    }
}

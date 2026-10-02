package com.sumirelabs.pcla.light.world;

import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureTaskTest {
    private static Map<Long,Object> chunks(CapturedRgbRegion.Bounds... bounds) {
        var result=new HashMap<Long,Object>();for(var b:bounds)for(long key:b.chunks())result.putIfAbsent(key,new Object());return result;
    }
    private static SharedOpticalInputs.Request<Object> full(CapturedRgbRegion.Bounds b,Map<Long,Object> chunks) {
        return new SharedOpticalInputs.Request<>(b,chunks,IntStream.range(0,b.cells()).toArray());
    }
    private static void fill(CapturedRgbRegion.Input value,int source) {
        value.source=source;value.blockedFaces=0;value.contextual=false;Arrays.fill(value.incoming,0);
    }
    @Test void tinyBudgetsDeduplicateOverlapsAndNeverCommitPartialPages() {
        var a=new CapturedRgbRegion.Bounds(-16,0,-16);var b=new CapturedRgbRegion.Bounds(0,0,-16);var tokens=chunks(a,b);
        for(int budget:new int[]{1,257}) {
            var store=new SharedOpticalInputs<Object>();var requests=new ArrayList<>(List.of(full(a,tokens),full(b,tokens)));
            var task=store.beginCapture(requests,(chunk,x,y,z,out)->fill(out,Math.floorMod(x*31+y*7+z*13,4096)));requests.clear();
            int calls=0;
            while(!task.complete()) {
                long before=task.readCells();task.advance(budget);assertTrue(task.readCells()-before<=budget);
                if(!task.complete()){assertThrows(IllegalStateException.class,task::result);assertEquals(0,store.stats().sections());}
                assertTrue(++calls<1000000);
            }
            assertEquals(2L*a.cells()-30L*46*46,task.readCells());assertEquals((long)a.cells()+b.cells(),task.selectedCells());
            assertThrows(UnsupportedOperationException.class,()->task.result().clear());
            for(var view:task.result())for(int cell=0;cell<view.bounds().cells();cell++) {
                var bounds=view.bounds();int x=bounds.x()-15+cell%bounds.inputX(),z=bounds.z()-15+cell/bounds.inputX()%bounds.inputZ(),y=bounds.y()-15+cell/(bounds.inputX()*bounds.inputZ());
                assertEquals(Math.floorMod(x*31+y*7+z*13,4096),view.emission(cell));
            }
        }
    }
    @Test void cancellationDuringAPageKeepsOldViewsAndAllowsAFreshCapture() {
        var b=new CapturedRgbRegion.Bounds(0,0,0);var tokens=chunks(b);var store=new SharedOpticalInputs<Object>();
        var old=store.capture(List.of(full(b,tokens)),(chunk,x,y,z,out)->fill(out,0)).getFirst();var before=store.stats();
        var task=store.beginCapture(List.of(full(b,tokens)),(chunk,x,y,z,out)->fill(out,0xf00));
        task.advance(b.cells()+17);assertEquals(17,task.readCells());assertFalse(task.complete());
        assertThrows(IllegalStateException.class,()->store.beginCapture(List.of(),(c,x,y,z,out)->fill(out,0)));
        assertEquals(before.pages(),store.stats().pages());assertEquals(before.arrayPayloadBytes(),store.stats().arrayPayloadBytes());
        task.cancel();task.cancel();assertThrows(IllegalStateException.class,task::result);assertThrows(IllegalStateException.class,()->task.advance(1));
        var current=store.capture(List.of(new SharedOpticalInputs.Request<>(b,tokens,new int[0])),(c,x,y,z,out)->fail()).getFirst();
        for(int i=0;i<b.cells();i++){assertEquals(0,old.emission(i));assertEquals(0,current.emission(i));}
    }
    @Test void readerAndLaterViewValidationFailuresDoNotCommitEarlierStaging() {
        var a=new CapturedRgbRegion.Bounds(0,0,0);var b=new CapturedRgbRegion.Bounds(64,0,0);var tokens=chunks(a,b);var store=new SharedOpticalInputs<Object>();
        var old=store.capture(List.of(full(a,tokens)),(c,x,y,z,out)->fill(out,0)).getFirst();
        int[] reads={0};var failed=store.beginCapture(List.of(full(a,tokens)),(c,x,y,z,out)->{if(++reads[0]==70)throw new IllegalStateException("reader");fill(out,0xf00);});
        assertThrows(IllegalStateException.class,()->{while(!failed.advance(17)){};});assertEquals("CANCELLED",failed.stage());
        var invalid=store.beginCapture(List.of(full(a,tokens),new SharedOpticalInputs.Request<>(b,tokens,new int[0])),(c,x,y,z,out)->fill(out,0x00f));
        assertThrows(IllegalStateException.class,()->{while(!invalid.advance(1024)){};});
        var current=store.capture(List.of(new SharedOpticalInputs.Request<>(a,tokens,new int[0])),(c,x,y,z,out)->fail()).getFirst();
        for(int i=0;i<a.cells();i++){assertEquals(0,current.emission(i));assertEquals(0,old.emission(i));}
    }
    @Test void allRequestCellsAndIdentitiesAreValidatedBeforeWorldReads() {
        var b=new CapturedRgbRegion.Bounds(0,0,0);var tokens=chunks(b);var store=new SharedOpticalInputs<Object>();
        var invalid=store.beginCapture(List.of(new SharedOpticalInputs.Request<>(b,tokens,new int[]{0,b.cells()})),(c,x,y,z,out)->fail());
        assertFalse(invalid.advance(1));assertThrows(IndexOutOfBoundsException.class,()->invalid.advance(1));assertEquals(0,store.stats().worldReads());
        var replacements=new HashMap<>(tokens);replacements.put(RegionPublication.key(-1,-1),new Object());
        var swapped=store.beginCapture(List.of(new SharedOpticalInputs.Request<>(b,tokens,new int[]{0}),new SharedOpticalInputs.Request<>(b,replacements,new int[]{0})),(c,x,y,z,out)->fail());
        assertThrows(IllegalStateException.class,()->swapped.advance(10));assertEquals(0,store.stats().worldReads());
    }
    @Test void retentionWaitsForTheActiveCaptureThenAppliesEvenAfterCancellation() {
        var b=new CapturedRgbRegion.Bounds(0,0,0);var tokens=chunks(b);var store=new SharedOpticalInputs<Object>();
        var old=store.capture(List.of(full(b,tokens)),(c,x,y,z,out)->fill(out,0xf00)).getFirst();int sectionCount=store.stats().sections();
        var task=store.beginCapture(List.of(full(b,tokens)),(c,x,y,z,out)->fill(out,0x00f));task.advance(b.cells()+1);
        store.retain(List.of());assertEquals(sectionCount,store.stats().sections());task.cancel();assertEquals(0,store.stats().sections());
        assertEquals(0xf00,old.emission(0));
        var fresh=store.beginCapture(List.of(full(b,tokens)),(c,x,y,z,out)->fill(out,0x0f0));store.retain(List.of(b));while(!fresh.advance(2048)){};
        assertEquals(sectionCount,store.stats().sections());assertEquals(0x0f0,fresh.result().getFirst().emission(0));
    }
    @Test void editsDuringCaptureCannotPublishAMixedRevisionAndTheNextCaptureConverges() {
        var b=new CapturedRgbRegion.Bounds(0,0,0);var tokens=chunks(b);var store=new SharedOpticalInputs<Object>();
        var old=store.capture(List.of(full(b,tokens)),(c,x,y,z,out)->fill(out,0)).getFirst();
        var gate=new RegionPublication<Object,SharedOpticalInputs.View>();gate.watch(b.chunks());tokens.forEach(gate::loaded);gate.publish(gate.begin(),old);
        gate.changedAt(RegionPublication.key(0,0),b.inputCell(0,0,0));var ticket=gate.begin();int[] color={0xf00};
        var task=store.beginCapture(List.of(full(b,tokens)),(c,x,y,z,out)->fill(out,color[0]));task.advance(b.cells()+10);
        color[0]=0x00f;gate.changedAt(RegionPublication.key(0,0),b.inputCell(0,0,0));while(!task.advance(512)){};
        assertFalse(gate.publish(ticket,task.result().getFirst()));assertSame(old,gate.displaySnapshot());
        var next=store.capture(List.of(full(b,tokens)),(c,x,y,z,out)->fill(out,color[0])).getFirst();assertTrue(gate.publish(gate.begin(),next));
        for(int i=0;i<b.cells();i++){assertEquals(0x00f,next.emission(i));assertEquals(0,old.emission(i));}
    }
}

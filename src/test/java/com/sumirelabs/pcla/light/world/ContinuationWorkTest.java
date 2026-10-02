package com.sumirelabs.pcla.light.world;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContinuationWorkTest {
    @Test void oneLongOwnerYieldsAndTheRemainingOwnerNeedsNoNewEdit() {
        var work=new ContinuationWork<Object>();var a=new Object();var b=new Object();var time=new AtomicLong();var seen=new ArrayList<Object>();
        work.offer(a);work.offer(a);work.offer(b);
        var first=work.drain(4,4,time::get,key->{seen.add(key);time.addAndGet(10);});
        assertEquals(List.of(a),seen);assertEquals(1,first.remaining());assertEquals(10,first.elapsedNanos());
        work.drain(4,4,time::get,seen::add);assertEquals(List.of(a,b),seen);assertEquals(0,work.size());
    }
    @Test void revisitingAnInFlightOwnerDoesNotStarveOlderQueuedOwners() {
        var work=new ContinuationWork<Object>();var a=new Object();var b=new Object();var seen=new ArrayList<Object>();
        work.offer(a);work.offer(b);
        work.drain(1,100,()->0,key->{seen.add(key);work.offer(a);});
        work.drain(1,100,()->0,seen::add);assertEquals(List.of(a,b),seen);
        work.drain(1,100,()->0,seen::add);assertEquals(List.of(a,b,a),seen);
    }
    @Test void equalOwnersAreIndependentAndRetirementClosesQueuedWorkImmediately() {
        var work=new ContinuationWork<String>();String a=new String("same"),b=new String("same");work.offer(a);work.offer(b);
        work.retain(List.of(b));assertEquals(1,work.size());var seen=new ArrayList<String>();
        work.drain(10,100,()->0,seen::add);assertSame(b,seen.getFirst());
        work.offer(a);work.close();work.offer(b);assertEquals(0,work.drain(1,100,()->0,key->fail()).visited());
    }
    @Test void ticketsAreTakenAtExecutionSoLateEditsAndChunkReplacementAreObserved() {
        var gate=new RegionPublication<Object,String>();gate.watch(Set.of(1L));gate.loaded(1,new Object());gate.publish(gate.begin(),"initial");
        var work=new ContinuationWork<RegionPublication<Object,String>>();work.offer(gate);
        gate.changedAt(1,15);var rejected=gate.begin();gate.changedAt(1,29);
        work.drain(1,10,()->0,owner->{var fresh=owner.begin();assertEquals(2,fresh.dirtyCells().cardinality());assertFalse(owner.publish(rejected,"stale"));assertTrue(owner.publish(fresh,"fresh"));});
        work.offer(gate);gate.unloaded(1);work.drain(1,10,()->0,owner->assertNull(owner.begin()));assertEquals(0,work.size());
        gate.loaded(1,new Object());work.offer(gate);work.drain(1,10,()->0,owner->assertTrue(owner.publish(owner.begin(),"replacement")));
    }
    @Test void exceptionDoesNotLoseOtherOwnersAndZeroBudgetStillMakesProgress() {
        var work=new ContinuationWork<Object>();var a=new Object();var b=new Object();work.offer(a);work.offer(b);
        assertThrows(IllegalStateException.class,()->work.drain(1,1,()->0,key->{throw new IllegalStateException();}));
        assertEquals(1,work.size());var seen=new ArrayList<Object>();assertEquals(1,work.drain(5,0,()->0,seen::add).visited());assertEquals(List.of(b),seen);
        assertThrows(IllegalArgumentException.class,()->work.drain(0,1,()->0,seen::add));
    }
}

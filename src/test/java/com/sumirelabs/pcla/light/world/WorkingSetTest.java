package com.sumirelabs.pcla.light.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkingSetTest {
    @Test void leastRecentlyUsedStateIsEvictedBeforeAllocationButCompletedRootSurvives() {
        var cache=new WorkingSet<Object,Object>(2);var a=new Object();var b=new Object();var c=new Object();
        var gate=new RegionPublication<Object,String>();gate.watch(Set.of(1L));gate.loaded(1,new Object());gate.publish(gate.begin(),"completed");
        Object av=cache.acquire(a,Object::new).value();cache.acquire(b,Object::new);
        assertSame(av,cache.acquire(a,()->{throw new AssertionError("Hit recreated");}).value());
        cache.acquire(c,()->{assertEquals(1,cache.stats().retained());return new Object();});
        assertEquals("completed",gate.snapshot());assertEquals("completed",gate.displaySnapshot());
        gate.changedAt(1,3);assertNull(gate.snapshot());assertEquals("completed",gate.displaySnapshot());
        assertTrue(cache.acquire(b,Object::new).created());assertEquals(2,cache.stats().retained());
        assertEquals(2,cache.stats().evictions());assertEquals(1,cache.stats().hits());assertEquals(4,cache.stats().creations());
    }
    @Test void identitiesRemainDistinctAndRetirementDoesNotCountAsCapacityEviction() {
        var cache=new WorkingSet<String,Object>(2);String a=new String("same"),b=new String("same");
        Object value=cache.acquire(a,Object::new).value();assertTrue(cache.acquire(b,Object::new).created());
        cache.retain(List.of(a));assertEquals(1,cache.stats().retained());assertEquals(0,cache.stats().evictions());
        assertSame(value,cache.acquire(a,Object::new).value());assertTrue(cache.acquire(b,Object::new).created());
        cache.remove(a);cache.remove(a);assertEquals(1,cache.stats().retained());cache.retain(List.of());assertEquals(0,cache.stats().retained());
    }
    @Test void failedCreationLeavesNoPartialEntryAndNextAcquireRebuilds() {
        var cache=new WorkingSet<Object,Object>(1);var a=new Object();var b=new Object();cache.acquire(a,Object::new);
        assertThrows(IllegalStateException.class,()->cache.acquire(b,()->{throw new IllegalStateException("capture allocation failed");}));
        assertEquals(0,cache.stats().retained());assertEquals(1,cache.stats().creations());
        assertTrue(cache.acquire(b,Object::new).created());assertFalse(cache.acquire(b,Object::new).created());
        assertThrows(IllegalArgumentException.class,()->new WorkingSet<>(0));assertThrows(IllegalArgumentException.class,()->new WorkingSet<>(65));
    }
    @Test void repeatedAdmissionAndTouchMatchesAnIndependentIdentityLruModel() {
        var cache=new WorkingSet<Object,Object>(4);var keys=new Object[12];Arrays.setAll(keys,i->new Object());
        var order=new ArrayList<Object>();var random=new Random(2914);
        for(int i=0;i<1000;i++) {
            Object key=keys[random.nextInt(keys.length)];boolean expectedNew=!order.remove(key);
            if(expectedNew && order.size()==4)order.remove(0);order.add(key);
            var result=cache.acquire(key,()->{assertTrue(cache.stats().retained()<4);return new Object();});
            assertEquals(expectedNew,result.created());assertEquals(order.size(),cache.stats().retained());
            if(i%11==0){Object retired=keys[random.nextInt(keys.length)];cache.remove(retired);order.remove(retired);}
        }
    }
}

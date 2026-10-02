package com.sumirelabs.pcla.light.world;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RgbContinuationWorkerTest {
    @Test void wakeupsDuringWorkCoalesceButAreNotLost() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var second=new CountDownLatch(1);
        var count=new AtomicInteger();
        try(var worker=new RgbContinuationWorker("rgb-wake-test",()->{
            if(count.incrementAndGet()==1){entered.countDown();await(release);}else second.countDown();
        },error->fail(error))) {
            worker.signal();assertTrue(entered.await(2,TimeUnit.SECONDS));
            for(int i=0;i<1000;i++)worker.signal();
            release.countDown();assertTrue(second.await(2,TimeUnit.SECONDS));
            worker.requestStop();assertTrue(worker.awaitStopped(2000));
            assertEquals(2,count.get());assertFalse(worker.stats().alive());assertFalse(worker.stats().pending());
        } finally{release.countDown();}
    }
    @Test void shutdownDuringWorkFinishesCurrentTurnAndDropsPendingTurn() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var count=new AtomicInteger();
        var worker=new RgbContinuationWorker("rgb-stop-test",()->{count.incrementAndGet();entered.countDown();await(release);},error->fail(error));
        try {
            worker.signal();assertTrue(entered.await(2,TimeUnit.SECONDS));worker.signal();worker.requestStop();worker.signal();
            release.countDown();assertTrue(worker.awaitStopped(2000));assertEquals(1,count.get());
            assertTrue(worker.stats().stopped());assertFalse(worker.stats().pending());
        } finally{release.countDown();worker.close();}
    }
    @Test void failingTurnReportsFailureAndCanBeWokenAgain() throws Exception {
        var failed=new CountDownLatch(1);var recovered=new CountDownLatch(1);var count=new AtomicInteger();
        try(var worker=new RgbContinuationWorker("rgb-recover-test",()->{
            if(count.incrementAndGet()==1)throw new IllegalStateException("test");recovered.countDown();
        },error->failed.countDown())) {
            worker.signal();assertTrue(failed.await(2,TimeUnit.SECONDS));worker.signal();assertTrue(recovered.await(2,TimeUnit.SECONDS));
        }
    }
    private static void await(CountDownLatch latch) {
        try{if(!latch.await(2,TimeUnit.SECONDS))throw new IllegalStateException("Timed out");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
    }
}

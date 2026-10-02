package com.sumirelabs.pcla.light.engine;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WordPhaseBudgetTest {
    static long field(RgbWordBlockPropagator e,String name)throws Exception {
        var f=RgbWordBlockPropagator.class.getDeclaredField(name);
        f.setAccessible(true);
        return ((Number)f.get(e)).longValue();
    }
    static void finish(RgbWordBlockPropagator e,int q)throws Exception {
        int calls=0;
        do {
            long before=field(e,"cursor")+field(e,"decreaseVisits")+field(e,"increaseVisits");
            boolean done=e.advance(q);
            long after=field(e,"cursor")+field(e,"decreaseVisits")+field(e,"increaseVisits");
            assertTrue(after-before>=0&&after-before<=q,"operations="+(after-before)+" budget="+q);
            if(done)break;
            assertTrue(++calls<100000);
        }
        while(true);
    }
    @Test void quantaPreserveEveryPhaseOutputWorkAndCeilings()throws Exception {
        RgbWordBlockPropagator.Work relight=null,update=null;
        for(int q:new int[] {
            1,2,7,257,8192
        }
        ) {
            var g=new WordBlockPropagatorTest.Grid(11,4,5);
            int a=g.at(4,2,2),b=g.at(6,2,2);
            g.sources[a]=0xf31;
            g.sources[b]=0x16f;
            g.filters[g.at(5,2,2)]=0x125;
            var e=new RgbWordBlockPropagator(g);
            e.startRelight();
            finish(e,q);
            assertArrayEquals(WordBlockPropagatorTest.reference(g),g.lights);
            if(relight==null)relight=e.completedWork();
            else assertEquals(relight,e.completedWork());
            g.sources[a]=0x16f;
            g.closedFaces[b]=3;
            e.startUpdate(a,b,a);
            finish(e,q);
            assertArrayEquals(WordBlockPropagatorTest.reference(g),g.lights);
            if(update==null)update=e.completedWork();
            else assertEquals(update,e.completedWork());
            e.startUpdate();
            assertTrue(e.advance(q));
            assertEquals(new RgbWordBlockPropagator.Work(0,0,0,0),e.completedWork());
            assertTrue(e.advance(q));
            assertThrows(IllegalArgumentException.class,()->e.advance(0));
            assertThrows(IllegalArgumentException.class,()->e.advance(-1));
        }
    }
    static final class Failing implements RgbWordBlockAccess {
        final WordBlockPropagatorTest.Grid g=new WordBlockPropagatorTest.Grid(11,1,1);
        int reads,failAt=Integer.MAX_VALUE;
        public int cellCount() {
            return g.cellCount();
        }
        public int neighbor(int c,int d) {
            return g.neighbor(c,d);
        }
        public int emission(int c) {
            return g.emission(c);
        }
        public int absorption(int c,int d) {
            return g.absorption(c,d);
        }
        public int light(int c) {
            if(++reads==failAt)throw new IllegalStateException("injected");
            return g.light(c);
        }
        public void light(int c,int v) {
            g.light(c,v);
        }
    }
    @Test void getterFailureCancelsAndRequiresRebuildForEveryQuantum()throws Exception {
        for(int q:new int[] {
            1,2,7,257,8192
        }
        ) {
            var a=new Failing();
            a.g.sources[5]=0xfff;
            var e=new RgbWordBlockPropagator(a);
            e.relight();
            a.g.sources[5]=0x16f;
            a.reads=0;
            a.failAt=5;
            e.startUpdate(5);
            assertThrows(IllegalStateException.class,()-> {
                while(!e.advance(q)) {
                }
                ;
            }
            );
            assertFalse(e.isRunning());
            assertThrows(IllegalStateException.class,()->e.startUpdate(5));
            assertThrows(IllegalStateException.class,e::completedWork);
            a.failAt=Integer.MAX_VALUE;
            e.startRelight();
            finish(e,q);
            assertArrayEquals(WordBlockPropagatorTest.reference(a.g),a.g.lights);
            e.startUpdate(5);
            e.advance(1);
            e.cancel();
            assertThrows(IllegalStateException.class,()->e.startUpdate(5));
            e.relight();
            assertArrayEquals(WordBlockPropagatorTest.reference(a.g),a.g.lights);
        }
    }
}

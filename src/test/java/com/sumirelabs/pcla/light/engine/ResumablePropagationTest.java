package com.sumirelabs.pcla.light.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResumablePropagationTest {
    @Test void blockBudgetsPreserveQueueWorkAndMatchIndependentScalarReference() {
        List<RgbBlockPropagator.Work> expectedWork=null;
        for(int budget:new int[]{1,7,1024}) {
            var grid=new RgbBlockPropagatorTest.Grid(7,9,6);var engine=new RgbBlockPropagator(grid);var random=new Random(31001);
            var work=new ArrayList<RgbBlockPropagator.Work>();
            grid.sources[grid.at(3,4,3)]=0xf53;engine.startRelight();
            assertThrows(IllegalStateException.class,engine::completedWork);finish(engine,budget);work.add(engine.completedWork());
            assertArrayEquals(RgbBlockPropagatorTest.reference(grid),grid.lights);
            for(int round=0;round<8;round++) {
                int[] changed=new int[30];
                for(int i=0;i<changed.length;i++){int cell=random.nextInt(grid.cellCount());changed[i]=cell;grid.sources[cell]=random.nextBoolean()?0:random.nextInt(4096);grid.filters[cell]=random.nextInt(4096);}
                engine.startUpdate(changed);Arrays.fill(changed,Integer.MAX_VALUE);
                assertThrows(IllegalStateException.class,engine::startRelight);finish(engine,budget);work.add(engine.completedWork());
                assertArrayEquals(RgbBlockPropagatorTest.reference(grid),grid.lights);
            }
            if(expectedWork==null)expectedWork=work;else assertEquals(expectedWork,work);
        }
    }
    @Test void skyBudgetsPreserveZeroLossColumnsAndMatchIndependentScalarReference() {
        List<RgbSkyPropagator.Work> expectedWork=null;
        for(int budget:new int[]{1,13,1024}) {
            var grid=new RgbSkyPropagatorTest.Grid(6,20,6);var engine=new RgbSkyPropagator(grid);var random=new Random(31002);
            var work=new ArrayList<RgbSkyPropagator.Work>();
            for(int z=0;z<6;z++)for(int x=0;x<6;x++)grid.boundary[grid.at(x,19,z)]=0xfff;
            engine.startRelight();assertThrows(IllegalStateException.class,engine::completedWork);finish(engine,budget);work.add(engine.completedWork());
            assertArrayEquals(RgbSkyPropagatorTest.reference(grid),grid.lights);
            for(int round=0;round<8;round++) {
                int[] changed=new int[25];
                for(int i=0;i<changed.length;i++){int cell=random.nextInt(grid.cellCount());changed[i]=cell;grid.opacity[cell]=random.nextInt(4096);if(grid.y(cell)==19)grid.boundary[cell]=random.nextBoolean()?0:0xfff;}
                engine.startUpdate(changed);Arrays.fill(changed,Integer.MAX_VALUE);
                assertThrows(IllegalStateException.class,engine::startRelight);finish(engine,budget);work.add(engine.completedWork());
                assertArrayEquals(RgbSkyPropagatorTest.reference(grid),grid.lights);
            }
            if(expectedWork==null)expectedWork=work;else assertEquals(expectedWork,work);
        }
    }
    @Test void oneOperationCannotChangeMoreThanOneCellAndItsSixNeighbors() {
        var grid=new RgbBlockPropagatorTest.Grid(5,5,5);grid.sources[62]=0xfff;var engine=new RgbBlockPropagator(grid);engine.startRelight();
        int turns=0;
        while(engine.isRunning()) {
            int[] previous=grid.lights.clone();engine.advance(1);int different=0;
            for(int i=0;i<previous.length;i++)if(previous[i]!=grid.lights[i])different++;
            assertTrue(different<=7);assertTrue(++turns<10000);
        }
        assertTrue(turns>grid.cellCount());assertArrayEquals(RgbBlockPropagatorTest.reference(grid),grid.lights);
    }
    @Test void cancellationAndReadFailureRequireRebuildAndNeverExposeCompletedWork() {
        var grid=new RgbSkyPropagatorTest.Grid(3,18,3);grid.boundary[grid.at(1,17,1)]=0xfff;var sky=new RgbSkyPropagator(grid);
        sky.startRelight();assertFalse(sky.advance(1));sky.cancel();
        assertFalse(sky.isRunning());assertThrows(IllegalStateException.class,sky::completedWork);
        assertThrows(IllegalStateException.class,()->sky.startUpdate(0));sky.startRelight();
        grid.failNextRead=true;assertThrows(IllegalStateException.class,()->sky.advance(1024));assertFalse(sky.isRunning());
        assertThrows(IllegalStateException.class,sky::completedWork);sky.startRelight();finish(sky,3);
        assertArrayEquals(RgbSkyPropagatorTest.reference(grid),grid.lights);
        var blockGrid=new RgbBlockPropagatorTest.Grid(5,5,5);blockGrid.sources[62]=0xfff;var block=new RgbBlockPropagator(blockGrid);
        block.startRelight();block.advance(126);block.cancel();assertThrows(IllegalStateException.class,()->block.startUpdate(62));
        block.startRelight();finish(block,1);assertArrayEquals(RgbBlockPropagatorTest.reference(blockGrid),blockGrid.lights);
    }
    @Test void invalidBudgetsAndRootsDoNotModifyThePriorState() {
        var grid=new RgbBlockPropagatorTest.Grid(3,3,3);grid.sources[13]=0xabc;var engine=new RgbBlockPropagator(grid);var work=engine.relight();int[] before=grid.lights.clone();
        assertThrows(IndexOutOfBoundsException.class,()->engine.startUpdate(0,27));assertArrayEquals(before,grid.lights);assertSame(work,engine.completedWork());
        engine.startRelight();assertThrows(IllegalArgumentException.class,()->engine.advance(0));assertTrue(engine.isRunning());finish(engine,1);
        assertArrayEquals(before,grid.lights);
    }
    private static void finish(RgbBlockPropagator engine,int budget){int turns=0;while(!engine.advance(budget))assertTrue(++turns<1000000);}
    private static void finish(RgbSkyPropagator engine,int budget){int turns=0;while(!engine.advance(budget))assertTrue(++turns<1000000);}
}

package com.sumirelabs.pcla.light.engine;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class RgbSkyPropagatorTest {
    static final class Grid implements RgbSkyAccess {
        final int width,height,depth;
        final int[] lights,boundary,opacity,faces;
        boolean failNextRead;
        Grid(int width,int height,int depth) {
            this.width=width; this.height=height; this.depth=depth;
            int count=width*height*depth;
            lights=new int[count]; boundary=new int[count]; opacity=new int[count]; faces=new int[count];
        }
        int at(int x,int y,int z) { return x+width*(z+depth*y); }
        int y(int cell) { return cell/(width*depth); }
        public int cellCount() { return lights.length; }
        public int neighbor(int cell,int direction) {
            int x=cell%width,z=cell/width%depth,y=y(cell);
            return switch(direction) {
                case 0 -> x+1<width ? cell+1 : -1;
                case 1 -> x>0 ? cell-1 : -1;
                case 2 -> y+1<height ? cell+width*depth : -1;
                case 3 -> y>0 ? cell-width*depth : -1;
                case 4 -> z+1<depth ? cell+width : -1;
                case 5 -> z>0 ? cell-width : -1;
                default -> throw new IllegalArgumentException();
            };
        }
        public int boundaryLight(int cell) { return boundary[cell]; }
        public int absorption(int cell,int direction) {
            if(failNextRead) { failNextRead=false; throw new IllegalStateException("capture invalidated"); }
            int target=neighbor(cell,direction);
            return target<0 || (faces[cell]&(1<<direction))!=0 || (faces[target]&(1<<(direction^1)))!=0 ? 0xfff : opacity[target];
        }
        public int light(int cell) { return lights[cell]; }
        public void light(int cell,int rgb) { lights[cell]=rgb; }
        void whiteTop() { for(int x=0;x<width;x++) for(int z=0;z<depth;z++) boundary[at(x,height-1,z)]=0xfff; }
    }

    /** Independent scalar relaxation, with no engine queue, RGB math or fixed 15-cell vertical limit. */
    static int[] reference(Grid grid) {
        int[] result=new int[grid.cellCount()];
        for(int shift:new int[]{0,4,8}) {
            int[] lane=new int[result.length];
            for(int cell=0;cell<lane.length;cell++) lane[cell]=grid.boundary[cell]>>shift&15;
            boolean changed; int passes=0;
            do {
                changed=false;
                for(int target=lane.length-1;target>=0;target--) for(int side=0;side<6;side++) {
                    int source=grid.neighbor(target,side); if(source<0) continue;
                    int opacity=grid.absorption(source,side^1)>>shift&15;
                    int candidate=lane[source]==15 && grid.y(source)>grid.y(target) && opacity==0
                            ? 15 : Math.max(0,lane[source]-Math.max(1,opacity));
                    if(candidate>lane[target]) { lane[target]=candidate; changed=true; }
                }
                assertTrue(++passes<=grid.cellCount()*15+1,"scalar sky reference did not converge");
            } while(changed);
            for(int cell=0;cell<lane.length;cell++) result[cell]|=lane[cell]<<shift;
        }
        return result;
    }

    @Test void onlyFullStrengthDownwardComponentsAvoidAttenuation() {
        assertEquals(0xfff,RgbSkyRules.attenuate(0xfff,0,true));
        assertEquals(0xeee,RgbSkyRules.attenuate(0xfff,0,false));
        assertEquals(0xf9a,RgbSkyRules.attenuate(0xfab,0,true));
        assertEquals(0xccc,RgbSkyRules.attenuate(0xfff,0x333,true));
        assertEquals(0xbbb,RgbSkyRules.attenuate(0xccc,0,true));
        assertEquals(0xf11,RgbSkyRules.attenuate(0xfff,0x0ee,true));
        assertEquals(0xf00,RgbSkyRules.attenuate(0xf11,0,true));
        assertEquals(0xfff,RgbSkyRules.dependentComponents(0xfff,0xfff,true));
        assertEquals(0,RgbSkyRules.dependentComponents(0xfff,0xfff,false));
        assertEquals(0,RgbSkyRules.dependentComponents(0xeee,0xeee,true));
    }

    @Test void removingSkyBoundaryDoesNotReseedTheDarkColumn() {
        var grid=new Grid(1,96,1);grid.whiteTop();var engine=new RgbSkyPropagator(grid);
        engine.relight();grid.boundary[95]=0;var work=engine.update(95);
        assertArrayEquals(reference(grid),grid.lights);
        assertEquals(2,work.increaseVisits(),"Only the changed root and its initial neighbor need revisiting");
    }

    @Test void clearedWeakSkyBoundarySurvivesAnUpstreamFaceClosing() {
        var grid=new Grid(1,96,1);grid.whiteTop();grid.boundary[20]=0x333;
        var engine=new RgbSkyPropagator(grid);engine.relight();assertEquals(0xfff,grid.lights[20]);
        grid.faces[70]=1<<3;engine.update(70);
        assertArrayEquals(reference(grid),grid.lights);
        assertEquals(0x333,grid.lights[20]);assertEquals(0x222,grid.lights[19]);
        grid.faces[70]=0;engine.update(70);assertArrayEquals(reference(grid),grid.lights);
    }

    @Test void fullSkyTravelsBeyondTheBlockLightHaloAndAnOpaqueRoofClearsItsWholeColumn() {
        var grid=new Grid(1,96,1); grid.whiteTop(); var engine=new RgbSkyPropagator(grid);
        engine.relight(); assertTrue(Arrays.stream(grid.lights).allMatch(rgb->rgb==0xfff));
        grid.opacity[70]=0xfff; engine.update(70);
        for(int cell=0;cell<=70;cell++) assertEquals(0,grid.light(cell),"below roof "+cell);
        for(int cell=71;cell<96;cell++) assertEquals(0xfff,grid.light(cell));
        assertArrayEquals(reference(grid),grid.lights);
        grid.opacity[70]=0; engine.update(70); assertTrue(Arrays.stream(grid.lights).allMatch(rgb->rgb==0xfff));
        grid.boundary[95]=0; engine.update(95); assertTrue(Arrays.stream(grid.lights).allMatch(rgb->rgb==0));
    }

    @Test void weakSkyDecaysDownwardAndBoundaryRemovalLeavesNoGhostLight() {
        var grid=new Grid(1,64,1); grid.boundary[63]=0xeee; var engine=new RgbSkyPropagator(grid);
        engine.relight(); assertEquals(0xddd,grid.light(62)); assertEquals(0x111,grid.light(50)); assertEquals(0,grid.light(49));
        grid.boundary[63]=0; engine.update(63);
        assertTrue(Arrays.stream(grid.lights).allMatch(rgb->rgb==0));
    }

    @Test void aColoredFilterRetainsItsFullChannelAndRemovalRestoresTheOthers() {
        var grid=new Grid(1,64,1); grid.whiteTop(); grid.opacity[40]=0x0ee;
        var engine=new RgbSkyPropagator(grid); engine.relight();
        assertEquals(0xf11,grid.light(40)); assertEquals(0xf00,grid.light(0));
        grid.opacity[40]=0xe0e; engine.update(40); assertEquals(0x0f0,grid.light(0));
        grid.opacity[40]=0; engine.update(40); assertEquals(0xfff,grid.light(0));
        assertArrayEquals(reference(grid),grid.lights);
    }

    @Test void lateralFullSkyRemainsIndependentWhenOneColumnBoundaryIsRemoved() {
        var grid=new Grid(3,32,3); grid.whiteTop(); var engine=new RgbSkyPropagator(grid); engine.relight();
        int center=grid.at(1,31,1); grid.boundary[center]=0; engine.update(center);
        for(int y=0;y<32;y++) { assertEquals(0xeee,grid.light(grid.at(1,y,1))); assertEquals(0xfff,grid.light(grid.at(0,y,1))); }
        assertArrayEquals(reference(grid),grid.lights);
    }

    @Test void mixedFacesFiltersAndBoundaryEditsMatchAnIndependentRebuild() {
        var grid=new Grid(4,32,3); grid.whiteTop(); var random=new Random(0x534b59);
        var engine=new RgbSkyPropagator(grid); engine.relight();
        int[] filters={0,0,0,0xfff,0x333,0x0ee,0xe0e,0xee0};
        for(int step=0;step<120;step++) {
            int cell=random.nextInt(grid.cellCount()),edited=cell;
            grid.opacity[cell]=filters[random.nextInt(filters.length)];
            grid.faces[cell]=random.nextInt(4)==0 ? 1<<random.nextInt(6) : 0;
            if(step%4==0) {
                cell=grid.at(random.nextInt(grid.width),grid.height-1,random.nextInt(grid.depth));
                grid.boundary[cell]=random.nextBoolean() ? 0xfff : random.nextInt(4096);
                // Notify the edited face/opacity endpoint as well as the independently edited boundary.
                engine.update(edited,cell);
            } else engine.update(cell,cell);
            assertArrayEquals(reference(grid),grid.lights,"edit "+step);
        }
    }

    @Test void invalidRootsAreRejectedBeforeChangingLight() {
        var grid=new Grid(1,32,1); grid.whiteTop(); var engine=new RgbSkyPropagator(grid); engine.relight();
        int[] before=grid.lights.clone();
        assertThrows(IndexOutOfBoundsException.class,()->engine.update(10,32));
        assertThrows(NullPointerException.class,()->engine.update((int[])null));
        assertArrayEquals(before,grid.lights);
    }

    @Test void failedAccessCanBeDiscardedAndRebuiltWithoutRetainingQueuedWork() {
        var grid=new Grid(2,32,2); grid.whiteTop(); var engine=new RgbSkyPropagator(grid);
        grid.failNextRead=true; assertThrows(IllegalStateException.class,engine::relight);
        engine.relight(); assertArrayEquals(reference(grid),grid.lights);
    }
}

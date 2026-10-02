package com.sumirelabs.pcla.light.chunk;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedChunkRgbEngineTest {
    record Pos(int x,int y,int z) {}
    record Optic(int emission,int faces,int[] incoming) {
        Optic(int emission,int opacity){this(emission,0,new int[]{opacity,opacity,opacity,opacity,opacity,opacity});}
    }
    static final Optic AIR=new Optic(0,0);
    static final int[] DX={1,-1,0,0,0,0},DY={0,0,1,-1,0,0},DZ={0,0,0,0,1,-1};
    static void finish(SharedChunkRgbEngine e) {
        int ticks=0;while(!e.advance(257))assertTrue(++ticks<100000,"Did not converge");
    }
    static SharedChunkRgbEngine.CellReader reader(Map<Pos,Optic> data) {
        return (x,y,z,out)-> {var v=data.getOrDefault(new Pos(x,y,z),AIR);out.emission=v.emission;out.blockedFaces=v.faces;
            System.arraycopy(v.incoming,0,out.incoming,0,6);};
    }
    @Test void crossChunkRemovalReloadAndNegativeCoordinates() {
        var inputs=new HashMap<Pos,Optic>();inputs.put(new Pos(-1,0,2),new Optic(0xf72,0));
        var e=new SharedChunkRgbEngine(-16,15,true);
        e.beginLoad(0,0,reader(inputs));finish(e);
        e.beginLoad(-1,0,reader(inputs));finish(e);
        assertEquals(0xe61,e.light(0,0,2,false));
        var retained=e.snapshot(0,0,false);
        e.beginUnload(-1,0);finish(e);
        assertEquals(0,e.light(0,0,2,false));
        assertEquals(0xe61,retained.get(0+2*16+16*256),"Published root must remain immutable");
        e.beginLoad(-1,0,reader(inputs));finish(e);
        assertEquals(0xe61,e.light(0,0,2,false));
        assertEquals(2,e.chunkCount());
    }
    @Test void fullSkyColumnDarkensAndRecoversWithoutGlobalRelight() {
        var data=new HashMap<Pos,Optic>();var e=new SharedChunkRgbEngine(0,31,true);
        e.beginLoad(0,0,reader(data));finish(e);assertEquals(0xfff,e.light(7,0,7,true));
        // Opaque roof completely closes the chunk, so no horizontal outside seeds exist.
        int[] roof=new int[256];
        for(int z=0;z<16;z++)for(int x=0;x<16;x++){roof[x+16*z]=x+16*z+31*256;data.put(new Pos(x,31,z),new Optic(0,0xfff));}
        e.beginUpdate(0,0,roof,reader(data));finish(e);assertEquals(0,e.light(7,0,7,true));
        data.clear();e.beginUpdate(0,0,roof,reader(data));finish(e);assertEquals(0xfff,e.light(7,0,7,true));
        assertTrue(e.completedWork().writes()>0);
    }
    @Test void invalidCaptureDoesNotModifyAttachedInputs() {
        var e=new SharedChunkRgbEngine(0,15,false);
        e.beginLoad(0,0,(x,y,z,v)->v.emission=0xf00);finish(e);
        assertThrows(IllegalArgumentException.class,()->e.beginUpdate(0,0,new int[]{0},(x,y,z,v)->v.emission=0x1000));
        assertEquals(0xf00,e.light(0,0,0,false));
        assertThrows(IllegalArgumentException.class,()->e.beginLoad(0,0,(x,y,z,v)->v.emission=-1));
        assertEquals(0xf00,e.light(0,0,0,false));
    }
    @Test void updatesAndChunkOrderMatchIndependentFixedPointSolver() {
        var data=new HashMap<Pos,Optic>();Random random=new Random(392511);
        Set<Long> loaded=new LinkedHashSet<>(List.of(key(-1,0),key(0,0),key(0,-1)));
        for(int i=0;i<240;i++) {
            int x=random.nextInt(32)-16,z=random.nextInt(32)-16,y=random.nextInt(16);
            if(!loaded.contains(key(x>>4,z>>4)))continue;
            int opacity=random.nextBoolean()?0:random.nextInt(4096);
            int[] faces=new int[6];for(int d=0;d<6;d++)faces[d]=random.nextInt(5)==0?0xfff:opacity;
            data.put(new Pos(x,y,z),new Optic(random.nextInt(5)==0?random.nextInt(4096):0,random.nextInt(64),faces));
        }
        var e=new SharedChunkRgbEngine(0,15,true);
        for(long k:loaded){e.beginLoad((int)k,(int)(k>>32),reader(data));finish(e);}
        compare(e,data,loaded);
        for(int i=0;i<20;i++) {
            int x=random.nextInt(32)-16,z=random.nextInt(16),y=random.nextInt(16);
            data.put(new Pos(x,y,z),new Optic(random.nextInt(4096),random.nextInt(4096)));
            e.beginUpdate(x>>4,z>>4,new int[]{(x&15)+(z&15)*16+y*256},reader(data));finish(e);
        }
        compare(e,data,loaded);
        e.beginUnload(0,0);finish(e);loaded.remove(key(0,0));compare(e,data,loaded);
        e.beginLoad(0,0,reader(data));finish(e);loaded.add(key(0,0));compare(e,data,loaded);
        // Reuse detached slots in a different order; old work IDs must never survive a drain.
        var order=new ArrayList<>(loaded);
        for(int round=0;round<3;round++) {
            Collections.shuffle(order,random);
            for(long k:order){e.beginUnload((int)k,(int)(k>>32));finish(e);loaded.remove(k);}
            assertEquals(0,e.chunkCount());
            Collections.shuffle(order,random);
            for(long k:order){e.beginLoad((int)k,(int)(k>>32),reader(data));finish(e);loaded.add(k);}
            compare(e,data,loaded);
        }
    }
    @Test void changedChunksAndPublicationAreBoundedToSettledOperation() {
        var e=new SharedChunkRgbEngine(0,15,false);
        e.beginLoad(0,0,(x,y,z,v)->{});
        assertThrows(IllegalStateException.class,()->e.snapshot(0,0,false));
        assertThrows(IllegalStateException.class,()->e.beginUnload(0,0));
        finish(e);assertEquals(Set.of(key(0,0)),e.drainChangedChunks());
        assertTrue(e.drainChangedChunks().isEmpty());
        e.beginUpdate(0,0,new int[0],(x,y,z,v)->{});finish(e);assertTrue(e.drainChangedChunks().isEmpty());
    }
    @Test void unchangedOpticsAndEmissionOnlyEditsDoNotRetraverseSky() {
        var e=new SharedChunkRgbEngine(0,255,true);
        e.beginLoad(0,0,(x,y,z,v)->{});finish(e);e.drainChangedChunks();
        var sky=e.snapshot(0,0,true);
        e.beginUpdate(0,0,new int[]{0,65535},(x,y,z,v)->{});finish(e);
        assertEquals(0,e.completedWork().writes());assertEquals(0,e.completedWork().increaseVisits());
        assertTrue(e.drainChangedChunks().isEmpty());
        e.beginUpdate(0,0,new int[]{0},(x,y,z,v)->v.emission=0xf00);finish(e);
        assertSame(sky,e.snapshot(0,0,true));assertEquals(0xf00,e.light(0,0,0,false));
    }
    @Test void warmCacheReconcilesChangedAbsentAndLaterNeighbors() {
        Map<Pos,Optic> data=new HashMap<>();data.put(new Pos(16,5,8),new Optic(0xf70,0));
        var original=new SharedChunkRgbEngine(0,31,true);
        original.beginLoad(0,0,reader(data));finish(original);original.beginLoad(1,0,reader(data));finish(original);
        byte[] cache=original.exportCache(0,0,"profile-a");
        assertEquals(0xe60,original.light(15,5,8,false));
        var restored=new SharedChunkRgbEngine(0,31,true);
        assertTrue(restored.beginLoad(0,0,reader(data),cache,"profile-a"));finish(restored);
        assertEquals(0,restored.light(15,5,8,false));
        compare(restored,data,Set.of(key(0,0)));
        data.put(new Pos(16,5,8),new Optic(0x08f,0));
        restored.beginLoad(1,0,reader(data));finish(restored);
        assertEquals(0x07e,restored.light(15,5,8,false));
        compare(restored,data,Set.of(key(0,0),key(1,0)));
        var otherOrder=new SharedChunkRgbEngine(0,31,true);
        otherOrder.beginLoad(1,0,reader(data));finish(otherOrder);
        assertTrue(otherOrder.beginLoad(0,0,reader(data),cache,"profile-a"));finish(otherOrder);
        compare(otherOrder,data,Set.of(key(0,0),key(1,0)));
    }
    @Test void invalidOrObsoleteCachesUseColdPath() {
        Map<Pos,Optic> data=new HashMap<>();var original=new SharedChunkRgbEngine(0,15,true);
        original.beginLoad(0,0,reader(data));finish(original);byte[] cache=original.exportCache(0,0,"a");
        var e=new SharedChunkRgbEngine(0,15,true);
        assertFalse(e.beginLoad(0,0,reader(data),cache,"b"));finish(e);
        cache[cache.length/2]^=1;
        var corrupted=new SharedChunkRgbEngine(0,15,true);
        assertFalse(corrupted.beginLoad(0,0,reader(data),cache,"a"));finish(corrupted);
        byte[] valid=original.exportCache(0,0,"a");data.put(new Pos(8,8,8),new Optic(0xf12,0x222));
        var changed=new SharedChunkRgbEngine(0,15,true);
        assertFalse(changed.beginLoad(0,0,reader(data),valid,"a"));finish(changed);
        assertEquals(0xf12,changed.light(8,8,8,false));
        var tall=new SharedChunkRgbEngine(0,31,true);
        assertFalse(tall.beginLoad(0,0,reader(data),valid,"a"));finish(tall);
        var noSky=new SharedChunkRgbEngine(0,15,false);
        assertFalse(noSky.beginLoad(0,0,reader(data),valid,"a"));finish(noSky);
    }
    @Test void randomizedTallWarmRestoresMatchFreshReference() {
        var data=new HashMap<Pos,Optic>();Random random=new Random(8920001);
        Set<Long> loaded=Set.of(key(-1,0),key(0,0));
        for(int i=0;i<1000;i++) {
            int[] incoming=new int[6];for(int d=0;d<6;d++)incoming[d]=random.nextInt(4096);
            data.put(new Pos(random.nextInt(32)-16,random.nextInt(64)-32,random.nextInt(16)),
                    new Optic(random.nextInt(5)==0?random.nextInt(4096):0,random.nextInt(64),incoming));
        }
        var original=new SharedChunkRgbEngine(-32,31,true);
        for(long k:loaded){original.beginLoad((int)k,(int)(k>>32),reader(data));finish(original);}
        byte[] left=original.exportCache(-1,0,"tall"),right=original.exportCache(0,0,"tall");
        var restored=new SharedChunkRgbEngine(-32,31,true);
        assertTrue(restored.beginLoad(0,0,reader(data),right,"tall"));finish(restored);
        assertTrue(restored.beginLoad(-1,0,reader(data),left,"tall"));finish(restored);
        compare(restored,data,loaded);
        // Changed neighbor roof removes old lateral sky paths into the unchanged cached chunk.
        for(int z=0;z<16;z++)for(int x=0;x<16;x++)data.put(new Pos(x,31,z),new Optic(0,4095));
        var changedNeighbor=new SharedChunkRgbEngine(-32,31,true);
        changedNeighbor.beginLoad(0,0,reader(data));finish(changedNeighbor);
        assertTrue(changedNeighbor.beginLoad(-1,0,reader(data),left,"tall"));finish(changedNeighbor);
        compare(changedNeighbor,data,loaded);
    }
    @Test void nonPowerOfTwoHeightUsesCorrectAddressesAcrossReusedSlots() {
        checkHeightAndSlotReuse(16,48);
    }
    @Test void powerOfTwoHeightWithNegativeMinimumUsesCorrectAddressesAcrossReusedSlots() {
        checkHeightAndSlotReuse(-32,64);
    }
    private static void checkHeightAndSlotReuse(int minimum,int height) {
        var data=new HashMap<Pos,Optic>();
        data.put(new Pos(-1,minimum+17,8),new Optic(0xf72,0));
        data.put(new Pos(16,minimum+18,8),new Optic(0x28f,0));
        data.put(new Pos(0,minimum+17,8),new Optic(0,0x321));
        var loaded=new LinkedHashSet<Long>();
        var engine=new SharedChunkRgbEngine(minimum,minimum+height-1,true);
        for(int x=-1;x<=1;x++) {
            engine.beginLoad(x,0,reader(data));finish(engine);loaded.add(key(x,0));
        }
        compare(engine,data,loaded);
        engine.beginUnload(-1,0);finish(engine);loaded.remove(key(-1,0));
        data.put(new Pos(8,minimum+20,-1),new Optic(0x4f2,0));
        engine.beginLoad(0,-1,reader(data));finish(engine);loaded.add(key(0,-1));
        compare(engine,data,loaded);
        // The new negative-Z chunk reused slot zero while its neighbor remains in slot one.
        data.put(new Pos(8,minimum+20,-1),new Optic(0xf04,0));
        engine.beginUpdate(0,-1,new int[]{8+(15<<4)+(20<<8)},reader(data));finish(engine);
        compare(engine,data,loaded);
        engine.beginLoad(-1,0,reader(data));finish(engine);loaded.add(key(-1,0));
        compare(engine,data,loaded);
    }
    static long key(int x,int z){return SharedChunkRgbEngine.chunkKey(x,z);}
    static void compare(SharedChunkRgbEngine e,Map<Pos,Optic> data,Set<Long> loaded) {
        for(boolean sky:new boolean[]{false,true}) {
            Map<Pos,Integer> ref=new HashMap<>();ArrayDeque<Pos> queue=new ArrayDeque<>();
            for(long k:loaded)for(int y=e.minimumY();y<=e.maximumY();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                Pos p=new Pos(((int)k)*16+x,y,((int)(k>>32))*16+z);var optic=data.getOrDefault(p,AIR);
                int value=sky?(y==e.maximumY()?attenuate(4095,optic.incoming[2],true):0):optic.emission;
                ref.put(p,value);if(value!=0)queue.add(p);
            }
            while(!queue.isEmpty()) {
                Pos p=queue.remove();int value=ref.get(p);var source=data.getOrDefault(p,AIR);
                for(int d=0;d<6;d++) {
                    Pos n=new Pos(p.x+DX[d],p.y+DY[d],p.z+DZ[d]);Integer old=ref.get(n);if(old==null)continue;
                    int opacity=(source.faces&(1<<d))!=0?4095:data.getOrDefault(n,AIR).incoming[d^1];
                    int candidate=attenuate(value,opacity,sky&&d==3),next=0;
                    for(int shift=0;shift<=8;shift+=4)next|=Math.max(old>>shift&15,candidate>>shift&15)<<shift;
                    if(next!=old){ref.put(n,next);queue.add(n);}
                }
            }
            for(var cell:ref.entrySet()) {
                Pos p=cell.getKey();assertEquals(cell.getValue().intValue(),e.light(p.x,p.y,p.z,sky),p+" sky="+sky);
            }
        }
    }
    static int attenuate(int value,int opacity,boolean down) {
        int result=0;for(int shift=0;shift<=8;shift+=4){int light=value>>shift&15,loss=opacity>>shift&15;
            result|=Math.max(0,light-(down&&light==15?loss:Math.max(1,loss)))<<shift;}return result;
    }
}

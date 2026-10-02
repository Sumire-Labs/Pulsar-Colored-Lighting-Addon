package com.sumirelabs.pcla.diagnostics;

import com.google.gson.JsonObject;
import com.sumirelabs.pcla.light.world.ExperimentalRgbBackend;

/** Fresh-input, three-scalar sky reference. Deliberately expensive diagnostic, not a benchmark. */
public final class SkyVerification {
    private SkyVerification() {}
    public static JsonObject verify(ExperimentalRgbBackend backend,int slot) {
        var published=backend.snapshot(slot);
        if(published==null || published.sky()==null) throw new IllegalStateException("Completed RGB sky unavailable");
        var result=compare(published,backend.captureSkyForVerification(slot));
        if(backend.snapshot(slot)!=published) throw new IllegalStateException("World changed during sky verification; retry");
        result.addProperty("slot",slot); return result;
    }
    public static JsonObject verifyColumn(ExperimentalRgbBackend backend,int x,int z) {
        var published=backend.columnSnapshot(x,z);
        if(published==null || published.sky()==null) throw new IllegalStateException("Completed column SKY light unavailable");
        var result=compare(published,backend.captureColumnSky(x,z));
        if(backend.columnSnapshot(x,z)!=published) throw new IllegalStateException("Column changed during sky verification; retry");
        result.addProperty("chunkX",x); result.addProperty("chunkZ",z); return result;
    }
    private static JsonObject compare(ExperimentalRgbBackend.Result published,com.sumirelabs.pcla.light.world.CapturedSkyRegion input) {
        int count=input.cellCount();
        int[] expected=new int[count];
        for(int shift=0;shift<=8;shift+=4) {
            int[] scalar=new int[count];
            for(int cell=0;cell<count;cell++) scalar[cell]=input.boundaryLight(cell)>>shift&15;
            boolean changed; int passes=0;
            do {
                changed=false;
                for(int target=count-1;target>=0;target--) for(int d=0;d<6;d++) {
                    int from=input.neighbor(target,d); if(from<0) continue;
                    int absorption=input.absorption(from,d^1)>>shift&15;
                    int candidate=d==2 && scalar[from]==15 && absorption==0 ? 15 : Math.max(0,scalar[from]-Math.max(1,absorption));
                    if(candidate>scalar[target]) { scalar[target]=candidate; changed=true; }
                }
                if(++passes>count*15+1) throw new IllegalStateException("Sky reference did not converge");
            } while(changed);
            for(int cell=0;cell<count;cell++) expected[cell]|=scalar[cell]<<shift;
        }
        int mismatches=0,lit=0,colored=0;
        for(int cell=0;cell<published.sky().light().cellCount();cell++) {
            int rgb=published.sky().light().get(cell);
            if(rgb!=expected[published.bounds().interiorIndex(cell)]) mismatches++;
            if(rgb!=0) lit++;
            if((rgb>>8)!=(rgb>>4&15) || (rgb&15)!=(rgb>>4&15)) colored++;
        }
        var result=new JsonObject(); result.addProperty("lane","sky");
        result.addProperty("compared",published.sky().light().cellCount()); result.addProperty("mismatches",mismatches);
        result.addProperty("lit",lit); result.addProperty("colored",colored); result.addProperty("revision",published.revision());
        result.addProperty("sampledSkyColumns",published.sky().sampledColumns()); result.addProperty("columnBlockReads",published.sky().columnBlockReads());
        return result;
    }
}

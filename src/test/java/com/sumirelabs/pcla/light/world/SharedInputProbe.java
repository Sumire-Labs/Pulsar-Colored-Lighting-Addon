package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.light.engine.RgbBlockPropagator;
import java.util.*;
import java.lang.management.ManagementFactory;

/** Detached paired-region capture + propagation comparison; excludes world APIs and networking. */
public final class SharedInputProbe {
    private static volatile int sink;
    private static final class Run {
        final boolean shared;
        final CapturedRgbRegion.Bounds[] bounds={new CapturedRgbRegion.Bounds(0,0,0,2,2,2),new CapturedRgbRegion.Bounds(16,0,0,2,2,2)};
        final CapturedRgbRegion[] regions=new CapturedRgbRegion[2];
        final RgbBlockPropagator[] engines=new RgbBlockPropagator[2];
        final Map<Long,Object> chunks=new HashMap<>();
        final SharedOpticalInputs<Object> store=new SharedOpticalInputs<>();
        final BitSet[] dirty=new BitSet[2];
        int source=0xf00; long reads,initialReads;
        Run(boolean shared) {
            this.shared=shared;
            for(int i=0;i<2;i++) {
                for(long key:bounds[i].chunks()) chunks.putIfAbsent(key,new Object());
                regions[i]=new CapturedRgbRegion(bounds[i],shared); engines[i]=new RgbBlockPropagator(regions[i]);
                dirty[i]=new BitSet(); dirty[i].set(bounds[i].inputCell(8,8,8));
            }
            update(true); initialReads=reads;
        }
        void read(int x,int y,int z,CapturedRgbRegion.Input value) {
            reads++; value.source=x==8 && y==8 && z==8 ? source : 0;
            value.contextual=false; value.blockedFaces=y<0?63:0;
            Arrays.fill(value.incoming,y<0?0xfff:x==9 && y==8 && z==8?0x0bb:0);
        }
        void update(boolean full) {
            List<SharedOpticalInputs.Request<Object>> requests=new ArrayList<>();
            int[][] plans=new int[2][];
            if(shared) for(int i=0;i<2;i++) {
                plans[i]=regions[i].planShared(dirty[i],full);
                requests.add(new SharedOpticalInputs.Request<>(bounds[i],chunks,plans[i],regions[i].sharedInputs()));
            }
            var views=shared?store.capture(requests,(chunk,x,y,z,value)->read(x,y,z,value)):null;
            for(int i=0;i<2;i++) {
                var b=bounds[i];
                var capture=shared?regions[i].refreshShared(views.get(i),plans[i].length):regions[i].refresh((cell,value)->
                        read(b.x()-15+cell%b.inputX(),b.y()-15+cell/(b.inputX()*b.inputZ()),b.z()-15+cell/b.inputX()%b.inputZ(),value),dirty[i],full);
                if(full) engines[i].relight(); else engines[i].update(capture.changedCells());
                sink=regions[i].interior().get(0);
            }
        }
    }
    public static void main(String[] args) {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counters unavailable");
        bean.setThreadAllocatedMemoryEnabled(true); long thread=Thread.currentThread().threadId();
        Run[] runs={new Run(false),new Run(true)};
        for(int warm=0;warm<200;warm++) for(var run:runs) { run.source=(warm&1)==0?0xf00:0x00f; run.update(false); }
        double[][] times=new double[2][5],bytes=new double[2][5];
        for(int batch=0;batch<5;batch++) for(int order=0;order<2;order++) {
            int method=(batch+order)&1; var run=runs[method];
            long allocated=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
            for(int i=0;i<200;i++) { run.source=(i&1)==0?0xf00:0x00f; run.update(false); }
            times[method][batch]=(System.nanoTime()-start)/200.0; bytes[method][batch]=(bean.getThreadAllocatedBytes(thread)-allocated)/200.0;
        }
        System.out.println("mode,initial_world_reads,bytes_per_paired_edit,ns_per_paired_edit,master_array_payload_bytes,uniform_pages,pages");
        for(int i=0;i<2;i++) {
            Arrays.sort(times[i]); Arrays.sort(bytes[i]); var stats=runs[i].store.stats();
            System.out.printf(Locale.ROOT,"%s,%d,%.1f,%.1f,%d,%d,%d%n",i==0?"duplicated":"shared",runs[i].initialReads,bytes[i][2],times[i][2],
                    i==0?15L*runs[i].bounds[0].cells()*2:stats.arrayPayloadBytes(),stats.uniformPages(),stats.pages());
        }
        for(int i=0;i<2;i++) for(int cell=0;cell<runs[0].bounds[i].outputCells();cell++)
            if(runs[0].regions[i].interior().get(cell)!=runs[1].regions[i].interior().get(cell)) throw new AssertionError("Propagation differs");
    }
}

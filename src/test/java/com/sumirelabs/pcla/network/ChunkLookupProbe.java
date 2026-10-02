package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Detached render-read cost, excluding index construction, network, meshes and OpenGL. */
public final class ChunkLookupProbe {
    private static volatile int sink;
    private static int linear(Map<Integer,RegionFrame> frames,int x,int y,int z,boolean sky) {
        for(var frame:frames.values()) if(frame.contains(x,y,z)) return sky?frame.sampleSky(x,y,z):frame.sample(x,y,z);
        return -1;
    }
    private static void run(ClientRegionStore.Snapshot root,int[][] queries,boolean indexed,int count) {
        int checksum=0;
        for(int i=0;i<count;i++) {
            var q=queries[i&255];
            if(indexed) checksum+=root.sample(q[0],q[1],q[2])^root.sampleSky(q[0],q[1],q[2]);
            else checksum+=linear(root.regions(),q[0],q[1],q[2],false)^linear(root.regions(),q[0],q[1],q[2],true);
        }
        sink=checksum;
    }
    public static void main(String[] args) {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counters unavailable");
        bean.setThreadAllocatedMemoryEnabled(true); long thread=Thread.currentThread().threadId();
        System.out.println("regions,case,method,bytes_per_pair,ns_per_pair");
        for(int count:new int[]{1,4}) {
            Map<Integer,RegionFrame> frames=new TreeMap<>(); var session=UUID.randomUUID();
            for(int slot=0;slot<count;slot++) {
                var b=new CapturedRgbRegion.Bounds(slot*32,0,0,2,2,2);
                short[] block=new short[b.outputCells()],sky=new short[block.length];
                for(int cell=0;cell<block.length;cell++) { block[cell]=(short)((cell*17)&4095);sky[cell]=(short)((cell*31+11)&4095); }
                frames.put(slot,RegionFrame.upsertV2(session,1,slot+1,0,slot,b,block,sky));
            }
            var root=new ClientRegionStore.Snapshot(session,1,count,0,"profile",frames,2);
            for(String scenario:new String[]{"first","last","miss","mixed"}) {
                int[][] queries=new int[256][3]; var random=new Random(20260913);
                for(int i=0;i<256;i++) {
                    int region=switch(scenario) { case "first"->0; case "last"->count-1; case "miss"->32; default->i%4==0?32:random.nextInt(count); };
                    queries[i]=new int[]{region*32+random.nextInt(32),random.nextInt(32),random.nextInt(32)};
                }
                for(int warm=0;warm<6;warm++) run(root,queries,(warm&1)==0,100000);
                double[][] bytes=new double[2][5],times=new double[2][5];
                for(int batch=0;batch<5;batch++) for(int order=0;order<2;order++) {
                    int method=(batch+order)&1; long allocated=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
                    run(root,queries,method==1,200000);
                    times[method][batch]=(System.nanoTime()-start)/200000.0; bytes[method][batch]=(bean.getThreadAllocatedBytes(thread)-allocated)/200000.0;
                }
                for(int method=0;method<2;method++) {
                    Arrays.sort(bytes[method]); Arrays.sort(times[method]);
                    System.out.printf(Locale.ROOT,"%d,%s,%s,%.2f,%.2f%n",count,scenario,method==0?"region_scan":"section_index",bytes[method][2],times[method][2]);
                }
                run(root,queries,false,256); int old=sink; run(root,queries,true,256);
                if(old!=sink) throw new AssertionError("Lookup results differ");
            }
        }
    }
}

package com.sumirelabs.pcla.light.storage;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Compares complete caller-owned exports; excludes wire encoding and socket transport. */
public final class SnapshotExportProbe {
    private static volatile short[] sink;
    private static void export(RgbLightVolume.Snapshot root, boolean bulk) {
        short[] values=new short[root.cellCount()];
        if(bulk) root.copyTo(values);
        else for(int i=0;i<values.length;i++) values[i]=(short)root.get(i);
        sink=values;
    }
    public static void main(String[] args) {
        var bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId();
        System.out.println("case,cells,method,median_bytes_per_export,median_ns_per_export");
        for(int size:new int[]{16,32}) for(String scenario:new String[]{"empty","sparse","dense"}) {
            var volume=new RgbLightVolume(size,size,size);
            if(!scenario.equals("empty")) for(int i=0;i<volume.cellCount();i++)
                if(scenario.equals("dense") || i%113==0) volume.set(i,(i*37+11)&4095);
            var root=volume.publish();
            for(int warm=0;warm<1000;warm++) export(root,(warm&1)==0);
            double[][] bytes=new double[2][5],times=new double[2][5];
            for(int batch=0;batch<5;batch++) for(int order=0;order<2;order++) {
                int method=(batch+order)&1;
                long allocated=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
                for(int i=0;i<500;i++) export(root,method==1);
                times[method][batch]=(System.nanoTime()-start)/500.0;
                bytes[method][batch]=(bean.getThreadAllocatedBytes(thread)-allocated)/500.0;
            }
            for(int method=0;method<2;method++) {
                Arrays.sort(bytes[method]); Arrays.sort(times[method]);
                System.out.printf(Locale.ROOT,"%s,%d,%s,%.1f,%.1f%n",scenario,root.cellCount(),
                        method==0 ? "cell_reads" : "row_copy",bytes[method][2],times[method][2]);
            }
        }
    }
}

package com.sumirelabs.pcla.light.world;

import java.lang.management.ManagementFactory;
import java.util.*;

/** Detached notification/ticket/consumption/publication cost, excluding light calculation. */
public final class DirtyTicketProbe {
    private static volatile long sink;
    private static final Sum visitor=new Sum();
    private static final class Sum implements java.util.function.IntConsumer {
        long value;
        @Override public void accept(int cell) { value+=cell; }
    }
    private static long consume(RegionPublication.Ticket<Object> ticket) {
        visitor.value=0; ticket.forEachDirty(visitor);return visitor.value;
    }
    private static void run(RegionPublication<Object,Object> gate,int[] edits,int repeats) {
        long sum=0;
        for(int i=0;i<repeats;i++) {
            for(int cell:edits) gate.changedAt(1,cell);
            var ticket=gate.begin(); sum+=consume(ticket);
            if(!gate.publish(ticket,gate)) throw new AssertionError("Rejected current ticket");
        }
        sink=sum;
    }
    public static void main(String[] args) {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counters unavailable");
        bean.setThreadAllocatedMemoryEnabled(true); long thread=Thread.currentThread().threadId();
        System.out.println("case,edits,bytes_per_batch,ns_per_batch");
        for(String scenario:new String[]{"low_one","high_one","scattered_32","dense_32768"}) {
            int[] edits=switch(scenario) {
                case "low_one" -> new int[]{15};
                case "high_one" -> new int[]{572401};
                case "scattered_32" -> java.util.stream.IntStream.range(0,32).map(i->i*17800+15).toArray();
                default -> java.util.stream.IntStream.range(0,32768).toArray();
            };
            var gate=new RegionPublication<Object,Object>(); gate.watch(Set.of(1L));gate.loaded(1,new Object());gate.publish(gate.begin(),gate);
            int count=edits.length>1000?50:500;
            for(int i=0;i<4;i++) run(gate,edits,count);
            long expected=Arrays.stream(edits).distinct().asLongStream().sum()*count;
            if(sink!=expected) throw new AssertionError("Missing or duplicate dirty cells");
            double[] bytes=new double[5],ns=new double[5];
            for(int i=0;i<5;i++) {
                long allocated=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
                run(gate,edits,count);
                ns[i]=(System.nanoTime()-start)/(double)count;
                bytes[i]=(bean.getThreadAllocatedBytes(thread)-allocated)/(double)count;
            }
            Arrays.sort(bytes);Arrays.sort(ns);
            System.out.printf(Locale.ROOT,"%s,%d,%.2f,%.2f%n",scenario,edits.length,bytes[2],ns[2]);
        }
    }
}

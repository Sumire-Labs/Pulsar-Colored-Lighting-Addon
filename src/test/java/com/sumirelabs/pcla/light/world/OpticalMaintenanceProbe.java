package com.sumirelabs.pcla.light.world;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.stream.IntStream;

/** Real optical-store operations with a detached reader; excludes Minecraft, light solving and networking. */
public final class OpticalMaintenanceProbe {
    private static volatile long sink;
    private final SharedOpticalInputs<Object> store=new SharedOpticalInputs<>();
    private final Map<Long,Object> chunks=new HashMap<>();
    private final Map<CapturedRgbRegion.Bounds,SharedOpticalInputs.View> views=new HashMap<>();
    private List<CapturedRgbRegion.Bounds> window;
    private int source,step;
    private final boolean compactPlans;
    private OpticalMaintenanceProbe(boolean compactPlans) {this.compactPlans=compactPlans;move(0);}
    private void read(Object chunk,int x,int y,int z,CapturedRgbRegion.Input out) {
        out.source=x==8 && y==65 && z==8?source:0;
        out.contextual=false;out.blockedFaces=y<64?63:0;
        Arrays.fill(out.incoming,y<64?0xfff:0);
    }
    private void move(int start) {
        var next=new ArrayList<CapturedRgbRegion.Bounds>();
        for(int z=0;z<3;z++)for(int x=start;x<start+3;x++) {
            var b=new CapturedRgbRegion.Bounds(x*16,0,z*16,1,16,1);next.add(b);
            for(long key:b.chunks())chunks.putIfAbsent(key,new Object());
            if(!views.containsKey(b)) {
                var request=compactPlans?SharedOpticalInputs.Request.full(b,chunks,null):
                    new SharedOpticalInputs.Request<>(b,chunks,IntStream.range(0,b.cells()).toArray());
                views.put(b,store.capture(List.of(request),this::read).getFirst());
            }
        }
        window=List.copyOf(next);views.keySet().retainAll(window);store.retain(window);sample();
    }
    private void maintain() {store.retain(window);sample();}
    private void edit() {
        source=source==0?0xf22:0;
        var b=window.getFirst();int cell=b.inputCell(8,65,8);
        var before=views.get(b);int old=before.emission(cell);
        var request=new SharedOpticalInputs.Request<>(b,chunks,new int[]{cell},before);
        var after=store.capture(List.of(request),this::read).getFirst();views.put(b,after);
        if(after.emission(cell)!=source || before.emission(cell)!=old)throw new AssertionError("Unstable optical publication");
        maintain();
    }
    private void sample() {var stats=store.stats();sink+=stats.pages()+stats.uniformPages()+stats.arrayPayloadBytes();}
    private void operation(String mode) {
        switch(mode) {
            case "stable-maintenance" -> maintain();
            case "single-cell-edit" -> edit();
            case "window-movement" -> move(++step%2);
            default -> throw new IllegalArgumentException(mode);
        }
    }
    public static void main(String[] args) {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemorySupported())throw new IllegalStateException("Allocation counters unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().threadId();
        String label=args.length==0?"unspecified":args[0];
        System.out.println("variant,case,batch,iterations,ns_per_operation,bytes_per_operation,sections,pages,uniform_pages,array_payload_bytes");
        for(String mode:List.of("stable-maintenance","single-cell-edit","window-movement")) {
            var run=new OpticalMaintenanceProbe(!label.equals("before"));
            int warm=mode.equals("stable-maintenance")?30000:mode.equals("single-cell-edit")?2000:50;
            int count=mode.equals("stable-maintenance")?3000:mode.equals("single-cell-edit")?1000:20;
            for(int i=0;i<warm;i++)run.operation(mode);
            for(int batch=0;batch<5;batch++) {
                long allocated=bean.getThreadAllocatedBytes(tid),start=System.nanoTime();
                for(int i=0;i<count;i++)run.operation(mode);
                long elapsed=System.nanoTime()-start,bytes=bean.getThreadAllocatedBytes(tid)-allocated;
                var stats=run.store.stats();
                System.out.printf(Locale.ROOT,"%s,%s,%d,%d,%.1f,%.1f,%d,%d,%d,%d%n",label,mode,batch,count,
                    elapsed/(double)count,bytes/(double)count,stats.sections(),stats.pages(),stats.uniformPages(),stats.arrayPayloadBytes());
            }
            run.store.retain(List.of());var empty=run.store.stats();
            if(empty.sections()!=0 || empty.pages()!=0 || empty.uniformPages()!=0 || empty.arrayPayloadBytes()!=0)
                throw new AssertionError("Retired optical storage remains");
        }
    }
}

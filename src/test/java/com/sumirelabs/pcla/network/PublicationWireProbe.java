package com.sumirelabs.pcla.network;
import com.sumirelabs.pcla.light.storage.RgbLightVolume;
import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.security.MessageDigest;

/** Detached publication -> retained baseline -> packet encode/apply; never starts Minecraft. */
public final class PublicationWireProbe {
    static final UUID ID=new UUID(12,34);
    static final CapturedRgbRegion.Bounds B=new CapturedRgbRegion.Bounds(-16,-64,32,1,16,1);
    static Method snapshotFactory;
    static boolean steady;
    static String mode="synthetic-lifecycle";
    static volatile long sink;
    static short[] copy(RgbLightVolume.Snapshot s) {var a=new short[s.cellCount()];s.copyTo(a);return a;}
    static RegionFrame full(long sequence,RgbLightVolume.Snapshot b,RgbLightVolume.Snapshot s)throws Exception {
        return snapshotFactory==null?RegionFrame.upsertV4(ID,1,sequence,0,4,B,copy(b),copy(s)):
            (RegionFrame)snapshotFactory.invoke(null,4,ID,1L,sequence,0,4,B,b,s);
    }
    record Result(long nanos,long allocated,String digest,long bytes) {}
    static Result sample(boolean patch,int changed,int recipients,int iteration)throws Exception {
        if(steady)return steadySample(patch,changed,recipients);
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long thread=Thread.currentThread().threadId();
        long alloc=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
        var b=new RgbLightVolume.WordColumnVolume(256);var s=new RgbLightVolume(16,256,16);
        for(int y=0;y<16;y++){b.setRgb12(y*4096+7,0x123);s.set(y*4096+13,0xabc);}
        var rootsB=new ArrayList<RgbLightVolume.Snapshot>();var rootsS=new ArrayList<RgbLightVolume.Snapshot>();
        rootsB.add(b.publish());rootsS.add(s.publish());
        // Recipients deliberately retain different old generations while more updates publish.
        for(int generation=1;generation<=4;generation++) {
            for(int y=0;y<changed;y++){b.setRgb12(y*4096+7,0x123+generation);s.set(y*4096+13,0xabc-generation);}
            rootsB.add(b.publish());rootsS.add(s.publish());
        }
        long bytes=0;var outputs=new ArrayList<io.netty.buffer.ByteBuf>();
        for(int recipient=0;recipient<recipients;recipient++) {
            int old=recipient%4;var baseline=full(1,rootsB.get(old),rootsS.get(old));
            RegionFrameV4Codec.Packet packet;
            if(patch) {
                var d=SectionDelta.between(ID,1,2,1,0,4,B,rootsB.get(old),rootsS.get(old),rootsB.get(4),rootsS.get(4));
                packet=RegionFrameV4Codec.Packet.patch(d);
                var applied=d.apply(baseline);sink+=applied.color(7)+applied.skyColor(13);
            } else packet=RegionFrameV4Codec.Packet.full(full(2,rootsB.get(4),rootsS.get(4)));
            var out=Unpooled.buffer();outputs.add(out);RegionFrameV4Codec.encode(packet,out);bytes+=out.readableBytes();
            sink+=baseline.color(7);
        }
        long elapsed=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(thread)-alloc;
        // Verification is outside the timed/allocated region so hashing does not hide encode cost.
        var digest=MessageDigest.getInstance("SHA-256");
        for(var buffer:outputs)try {digest.update(buffer.nioBuffer());}finally {buffer.release();}
        return new Result(elapsed,allocated,HexFormat.of().formatHex(digest.digest()),bytes);
    }
    static void initializeSteady(RgbLightVolume.WordColumnVolume block,RgbLightVolume sky) {
        if(mode.equals("steady-wire-mixed")) {
            var rng=new Random(47002);
            for(int cell=0;cell<B.outputCells();cell++){block.setRgb12(cell,1+rng.nextInt(4095));sky.set(cell,1+rng.nextInt(4095));}
        } else if(mode.equals("steady-wire-uniform")) {
            for(int cell=0;cell<B.outputCells();cell++){block.setRgb12(cell,0x123);sky.set(cell,0xabc);}
        }
        // The same deterministic dirty targets support comparable historical generations in every density.
        for(int y=0;y<16;y++){block.setRgb12(y*4096+7,0x123);sky.set(y*4096+13,0xabc);}
    }
    /** Old decoded client data and server generations are fixtures, never candidate-backed client frames. */
    static Result steadySample(boolean patch,int changed,int recipients)throws Exception {
        var b=new RgbLightVolume.WordColumnVolume(256);var s=new RgbLightVolume(16,256,16);
        initializeSteady(b,s);
        var rootsB=new ArrayList<RgbLightVolume.Snapshot>();var rootsS=new ArrayList<RgbLightVolume.Snapshot>();
        rootsB.add(b.publish());rootsS.add(s.publish());
        for(int generation=1;generation<=3;generation++) {
            for(int y=0;y<changed;y++){b.setRgb12(y*4096+7,0x123+generation);s.set(y*4096+13,0xabc-generation);}
            rootsB.add(b.publish());rootsS.add(s.publish());
        }
        var clientBaselines=new RegionFrame[recipients];
        for(int recipient=0;recipient<recipients;recipient++) {
            int old=recipient%4;
            clientBaselines[recipient]=RegionFrame.upsertV4(ID,1,1,0,4,B,copy(rootsB.get(old)),copy(rootsS.get(old)));
        }
        // Dirty working writes are prepared; the timed delivery begins at latest publication.
        for(int y=0;y<changed;y++){b.setRgb12(y*4096+7,0x127);s.set(y*4096+13,0xab8);}
        var outputs=new ArrayList<io.netty.buffer.ByteBuf>();var received=new ArrayList<RegionFrame>();long bytes=0;
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long thread=Thread.currentThread().threadId();
        long alloc=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
        var nextB=b.publish();var nextS=s.publish();
        for(int recipient=0;recipient<recipients;recipient++) {
            int old=recipient%4;
            var packet=patch?RegionFrameV4Codec.Packet.patch(SectionDelta.between(ID,1,2,1,0,4,B,rootsB.get(old),rootsS.get(old),nextB,nextS))
                    :RegionFrameV4Codec.Packet.full(full(2,nextB,nextS));
            var out=Unpooled.buffer();outputs.add(out);RegionFrameV4Codec.encode(packet,out);bytes+=out.readableBytes();
            var decoded=RegionFrameV4Codec.decode(out.duplicate());
            var installed=decoded.delta()==null?decoded.frame():decoded.delta().apply(clientBaselines[recipient]);
            received.add(installed);sink+=installed.color(7)+installed.skyColor(13);
        }
        long elapsed=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(thread)-alloc;
        // Exact decoded/applied values and encoded digests are checked after timing.
        for(var frame:received)for(int cell=0;cell<frame.cells();cell++)
            if(frame.color(cell)!=nextB.get(cell) || frame.skyColor(cell)!=nextS.get(cell))throw new AssertionError("Decoded client payload mismatch "+cell);
        var digest=MessageDigest.getInstance("SHA-256");
        for(var buffer:outputs)try {digest.update(buffer.nioBuffer());}finally {buffer.release();}
        return new Result(elapsed,allocated,HexFormat.of().formatHex(digest.digest()),bytes);
    }
    public static void main(String[] args)throws Exception {
        boolean candidate=Boolean.parseBoolean(args[0]);
        mode=args.length>1?args[1]:"synthetic-lifecycle";steady=mode.startsWith("steady-wire");
        if(candidate)snapshotFactory=RegionFrame.class.getMethod("upsertSnapshot",int.class,UUID.class,long.class,long.class,int.class,int.class,CapturedRgbRegion.Bounds.class,RgbLightVolume.Snapshot.class,RgbLightVolume.Snapshot.class);
        for(boolean patch:new boolean[]{false,true})for(int changed:new int[]{0,1,12})for(int recipients:new int[]{1,4}) {
            if(patch && changed==0)continue;
            for(int i=0;i<100;i++)sample(patch,changed,recipients,i);
            long[] ns=new long[200],alloc=new long[200];var raw=new Result[200];String digest=null;long bytes=-1;
            for(int i=0;i<200;i++) {
                var r=sample(patch,changed,recipients,i);raw[i]=r;ns[i]=r.nanos;alloc[i]=r.allocated;
                if(digest==null){digest=r.digest;bytes=r.bytes;}
                else if(!digest.equals(r.digest) || bytes!=r.bytes)throw new AssertionError("Unstable measured wire payload at iteration "+i);
            }
            // Record every original measurement before sorting; output is outside all sample timers.
            for(int i=0;i<raw.length;i++) {
                var r=raw[i];System.out.printf(Locale.ROOT,"RAW,%s,%s,%d,%d,%d,%d,%d,%s,%d%n",
                        candidate?"candidate":"baseline",patch?"patch":"full",changed,recipients,i,r.nanos,r.allocated,r.digest,r.bytes);
            }
            Arrays.sort(ns);Arrays.sort(alloc);
            System.out.printf(Locale.ROOT,"%s,%s,%d,%d,%d,%d,%d,%s,%d%n",candidate?"candidate":"baseline",patch?"patch":"full",changed,recipients,(ns[99]+ns[100])/2,ns[189],(alloc[99]+alloc[100])/2,digest,bytes);
        }
    }
}

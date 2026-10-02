package com.sumirelabs.pcla.network;

import java.util.*;

/** Apply on the client thread; immutable roots are safe for render workers. */
public final class ClientRegionStore {
    public static final class Snapshot {
        private final UUID session;
        private final long epoch,sequence;
        private final int dimension,protocol;
        private final String profile;
        private final Map<Integer,RegionFrame> regions;
        private final ChunkLightIndex index;
        public Snapshot(UUID session,long epoch,long sequence,int dimension,String profile,Map<Integer,RegionFrame> regions) { this(session,epoch,sequence,dimension,profile,regions,1); }
        public Snapshot(UUID session,long epoch,long sequence,int dimension,String profile,Map<Integer,RegionFrame> regions,int protocol) {
            this.session=session; this.epoch=epoch; this.sequence=sequence; this.dimension=dimension; this.profile=profile; this.protocol=protocol;
            this.regions=Collections.unmodifiableMap(new TreeMap<>(regions)); index=new ChunkLightIndex(this.regions);
        }
        public UUID session() { return session; }
        public long epoch() { return epoch; }
        public long sequence() { return sequence; }
        public int dimension() { return dimension; }
        public int protocol() { return protocol; }
        public String profile() { return profile; }
        public Map<Integer,RegionFrame> regions() { return regions; }
        public ChunkLightIndex index() { return index; }
        public int sample(int x,int y,int z) { return index.block(x,y,z); }
        public int sampleSky(int x,int y,int z) { return index.sky(x,y,z); }
        @Override public boolean equals(Object value) {
            return value instanceof Snapshot other && Objects.equals(session,other.session) && epoch==other.epoch && sequence==other.sequence
                    && dimension==other.dimension && protocol==other.protocol && Objects.equals(profile,other.profile) && regions.equals(other.regions);
        }
        @Override public int hashCode() { return Objects.hash(session,epoch,sequence,dimension,profile,regions,protocol); }
        @Override public String toString() { return "Snapshot[session="+session+", epoch="+epoch+", sequence="+sequence+", dimension="+dimension+", profile="+profile+", regions="+regions+", protocol="+protocol+"]"; }
    }
    private volatile Snapshot visible;
    private long retiredEpoch;
    public enum DeltaResult { APPLIED, IGNORED, RESYNC }
    public synchronized DeltaResult applyDelta(SectionDelta delta,int currentDimension) {
        var previous=visible;
        if(previous==null || previous.protocol()!=4 || delta.dimension!=currentDimension || delta.dimension!=previous.dimension()
                || !delta.session.equals(previous.session()) || delta.epoch!=previous.epoch() || delta.sequence<=previous.sequence())return DeltaResult.IGNORED;
        var baseline=previous.regions().get(delta.slot);
        if(!delta.matches(baseline))return DeltaResult.RESYNC;
        var next=new TreeMap<>(previous.regions());next.put(delta.slot,delta.apply(baseline));
        visible=new Snapshot(previous.session(),previous.epoch(),delta.sequence,previous.dimension(),previous.profile(),next,4);
        return DeltaResult.APPLIED;
    }
    /** Diagnostic fault injection: discard only this slot's baseline, preserving stream order. */
    public synchronized RegionFrame forgetColumn(int slot) {
        if(visible==null || visible.protocol()!=4 || slot<4)return null;
        var old=visible.regions().get(slot);if(old==null)return null;
        var next=new TreeMap<>(visible.regions());next.remove(slot);
        visible=new Snapshot(visible.session(),visible.epoch(),visible.sequence(),visible.dimension(),visible.profile(),next,4);return old;
    }
    public Snapshot snapshot() { return visible; }
    public synchronized boolean apply(RegionFrame frame,int currentDimension) {
        if(frame.dimension!=currentDimension) return false;
        var previous=visible;
        if(frame.kind==RegionFrame.Kind.HELLO) {
            if(frame.epoch<=retiredEpoch) return false;
            if(previous!=null && frame.epoch<=previous.epoch())
                return frame.epoch==previous.epoch() && frame.dimension==previous.dimension()
                        && frame.session.equals(previous.session()) && frame.profile.equals(previous.profile()) && frame.protocol==previous.protocol();
            visible=new Snapshot(frame.session,frame.epoch,0,frame.dimension,frame.profile,Map.of(),frame.protocol);
            return true;
        }
        if(previous==null || !frame.session.equals(previous.session()) || frame.epoch!=previous.epoch()
                || frame.sequence<=previous.sequence() || frame.dimension!=previous.dimension() || frame.protocol!=previous.protocol()) return false;
        var next=new TreeMap<>(previous.regions());
        if(frame.kind==RegionFrame.Kind.REMOVE) next.remove(frame.slot); else next.put(frame.slot,frame);
        visible=new Snapshot(previous.session(),previous.epoch(),frame.sequence,previous.dimension(),previous.profile(),next,previous.protocol());
        return true;
    }
    public synchronized void invalidateWorld() {
        if(visible!=null) retiredEpoch=Math.max(retiredEpoch,visible.epoch());
        visible=null;
    }
    public synchronized void clear() { visible=null; retiredEpoch=0; }
}

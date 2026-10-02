package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.storage.RgbLightVolume;
import com.sumirelabs.pcla.light.storage.RgbSection;
import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import java.util.*;
import io.netty.buffer.ByteBuf;

/** Complete changed sections relative to one exact per-slot baseline. Both lanes publish together. */
public final class SectionDelta {
    public final UUID session;
    public final long epoch,sequence,baseSequence;
    public final int dimension,slot,lanes,blockMask,skyMask;
    public final CapturedRgbRegion.Bounds bounds;
    private final RegionFrame.Lane[] block,sky;

    public SectionDelta(UUID session,long epoch,long sequence,long baseSequence,int dimension,int slot,
                        CapturedRgbRegion.Bounds bounds,int lanes,short[][] block,short[][] sky) {
        this(session,epoch,sequence,baseSequence,dimension,slot,bounds,lanes,
                validate(block,bounds.sectionsY()),validate(sky,bounds.sectionsY()));
    }
    private SectionDelta(UUID session,long epoch,long sequence,long baseSequence,int dimension,int slot,
                         CapturedRgbRegion.Bounds bounds,int lanes,RegionFrame.Lane[] block,RegionFrame.Lane[] sky) {
        this.session=Objects.requireNonNull(session);this.bounds=Objects.requireNonNull(bounds);
        if(epoch<=0 || baseSequence<=0 || sequence<=baseSequence || slot<4 || slot>=256
                || bounds.sectionsX()!=1 || bounds.sectionsZ()!=1 || lanes<1 || lanes>3)
            throw new IllegalArgumentException("Invalid column delta header");
        this.epoch=epoch;this.sequence=sequence;this.baseSequence=baseSequence;this.dimension=dimension;this.slot=slot;this.lanes=lanes;
        this.block=block;this.sky=sky;
        blockMask=mask(this.block);skyMask=mask(this.sky);
        if((blockMask|skyMask)==0 || blockMask!=0 && (lanes&1)==0 || skyMask!=0 && (lanes&2)==0)
            throw new IllegalArgumentException("Invalid delta lane mask");
    }
    private static RegionFrame.Lane[] validate(short[][] source,int count) {
        if(source==null || source.length!=count)throw new IllegalArgumentException("Invalid section table");
        RegionFrame.Lane[] copy=new RegionFrame.Lane[count];
        for(int section=0;section<count;section++)if(source[section]!=null) {
            if(source[section].length!=4096)throw new IllegalArgumentException("Invalid section payload");
            copy[section]=RegionFrame.Lane.copy(source[section]);
        }
        return copy;
    }
    private static int mask(RegionFrame.Lane[] values){int mask=0;for(int i=0;i<values.length;i++)if(values[i]!=null)mask|=1<<i;return mask;}
    public int value(boolean skyLane,int section,int cell){return (skyLane?sky:block)[section].get(cell);}
    void writeSection(boolean skyLane,int section,ByteBuf out){(skyLane?sky:block)[section].writeTo(out);}
    public int encodedBytes(){return 68+8192*(Integer.bitCount(blockMask)+Integer.bitCount(skyMask));}
    public boolean matches(RegionFrame baseline) {
        return baseline!=null && baseline.protocol==4 && baseline.kind==RegionFrame.Kind.UPSERT
                && baseline.session.equals(session) && baseline.epoch==epoch && baseline.sequence==baseSequence
                && baseline.slot==slot && baseline.dimension==dimension && baseline.bounds.equals(bounds)
                && lanes==((baseline.hasBlock()?1:0)|(baseline.hasSky()?2:0));
    }
    public RegionFrame apply(RegionFrame baseline) {
        if(!matches(baseline))throw new IllegalArgumentException("Column delta baseline mismatch");
        var sections=new RegionFrame.Section[bounds.sectionsY()];
        for(int y=0;y<sections.length;y++) {
            var previous=baseline.section(0,y,0);
            sections[y]=((blockMask|skyMask)&(1<<y))==0?previous:previous.replace(block[y],sky[y]);
        }
        return baseline.patched(sequence,sections);
    }
    /** Caller must use a full replacement on a bounds or lane-presence change. Null means equal values. */
    public static SectionDelta between(UUID session,long epoch,long sequence,long baseSequence,int dimension,int slot,
                                       CapturedRgbRegion.Bounds bounds,RgbLightVolume.Snapshot oldBlock,RgbLightVolume.Snapshot oldSky,
                                       RgbLightVolume.Snapshot nextBlock,RgbLightVolume.Snapshot nextSky) {
        if((oldBlock==null)!=(nextBlock==null) || (oldSky==null)!=(nextSky==null))throw new IllegalArgumentException("Lane presence changed");
        int lanes=(nextBlock!=null?1:0)|(nextSky!=null?2:0);
        RegionFrame.Lane[] block=changes(bounds,oldBlock,nextBlock),sky=changes(bounds,oldSky,nextSky);
        if((mask(block)|mask(sky))==0)return null;
        return new SectionDelta(session,epoch,sequence,baseSequence,dimension,slot,bounds,lanes,block,sky);
    }
    private static RegionFrame.Lane[] changes(CapturedRgbRegion.Bounds bounds,RgbLightVolume.Snapshot before,RgbLightVolume.Snapshot after) {
        RegionFrame.Lane[] result=new RegionFrame.Lane[bounds.sectionsY()];
        if(after==null)return result;
        if(!before.isColumn() || !after.isColumn() || before.cellCount()!=bounds.outputCells() || after.cellCount()!=bounds.outputCells()
                || before.sectionCount()!=result.length || after.sectionCount()!=result.length)
            throw new IllegalArgumentException("Column shape changed");
        for(int y=0;y<result.length;y++) {
            var old=before.section(y);var next=after.section(y);if(old==next)continue;
            // Rebuilding an evicted owner may produce new identities with unchanged values.
            if(equal(old,next))continue;
            result[y]=RegionFrame.Lane.snapshot(next);
        }
        return result;
    }
    private static boolean equal(RgbSection.Snapshot left,RgbSection.Snapshot right) {
        return left==null?right==null || right.contentEquals(null):left.contentEquals(right);
    }
}

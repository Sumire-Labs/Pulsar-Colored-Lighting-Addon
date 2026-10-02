package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.PackedRgb;
import com.sumirelabs.pcla.light.storage.RgbLightVolume;
import com.sumirelabs.pcla.light.storage.RgbSection;
import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import com.sumirelabs.pcla.light.world.RegionDirectory;
import java.util.*;
import io.netty.buffer.ByteBuf;

/** Immutable bounded wire frame; server simulation owns all light values. */
public final class RegionFrame {
    public static final int MAX_STREAM_SLOTS=256;
    public enum Kind { HELLO, UPSERT, REMOVE }
    public final Kind kind;
    public final UUID session;
    public final long epoch,sequence;
    public final int dimension,slot;
    public final int protocol;
    public final String profile;
    public final CapturedRgbRegion.Bounds bounds;
    private final short[] colors,skyColors;
    private final Section[] sections;
    private final boolean blockPresent,skyPresent;
    private RegionFrame(Kind kind, UUID session, long epoch, long sequence, int dimension, int slot,
                        String profile, CapturedRgbRegion.Bounds bounds, short[] colors) {
        this(1,kind,session,epoch,sequence,dimension,slot,profile,bounds,colors,null);
    }
    private RegionFrame(int protocol,Kind kind,UUID session,long epoch,long sequence,int dimension,int slot,
                        String profile,CapturedRgbRegion.Bounds bounds,short[] colors,short[] skyColors) {
        if(protocol<1 || protocol>4) throw new IllegalArgumentException("Invalid wire protocol");
        this.protocol=protocol;
        this.kind=Objects.requireNonNull(kind); this.session=Objects.requireNonNull(session);
        if(epoch<=0 || sequence<0) throw new IllegalArgumentException("Invalid stream generation");
        if(kind==Kind.HELLO) {
            if(sequence!=0 || profile==null || !profile.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid hello");
        } else if(sequence==0 || slot<0 || slot>=(protocol>=3?MAX_STREAM_SLOTS:RegionDirectory.MAX_REGIONS)) throw new IllegalArgumentException("Invalid region header");
        if(kind==Kind.UPSERT) {
            if(bounds==null || colors==null && skyColors==null || protocol==1 && (colors==null || skyColors!=null)) throw new IllegalArgumentException("Invalid region payload");
            if(protocol<3 || slot<RegionDirectory.MAX_REGIONS) {
                if(bounds.sectionsY()>2)throw new IllegalArgumentException("Legacy region transport supports at most two vertical sections");
            } else if(bounds.sectionsX()!=1 || bounds.sectionsZ()!=1)throw new IllegalArgumentException("Automatic frames own one horizontal chunk");
            validateColors(colors,bounds.outputCells()); validateColors(skyColors,bounds.outputCells());
        }
        this.epoch=epoch; this.sequence=sequence; this.dimension=dimension; this.slot=slot;
        this.profile=profile; this.bounds=bounds; this.colors=colors==null ? null : colors.clone();
        this.skyColors=skyColors==null ? null : skyColors.clone();
        blockPresent=colors!=null;skyPresent=skyColors!=null;
        if(kind==Kind.UPSERT) {
            sections=new Section[bounds.sectionsX()*bounds.sectionsY()*bounds.sectionsZ()];
            for(int y=0;y<bounds.sectionsY();y++) for(int z=0;z<bounds.sectionsZ();z++) for(int x=0;x<bounds.sectionsX();x++)
                sections[x+bounds.sectionsX()*(z+bounds.sectionsZ()*y)]=new Section(this.colors,this.skyColors,
                        16*x+bounds.sizeX()*(16*z+bounds.sizeZ()*16*y),bounds.sizeX(),bounds.sizeX()*bounds.sizeZ());
        } else sections=null;
    }
    /** Only immutable published sections are retained, including during delayed serialization. */
    public static RegionFrame upsertSnapshot(int protocol,UUID session,long epoch,long sequence,int dimension,int slot,
                                            CapturedRgbRegion.Bounds bounds,RgbLightVolume.Snapshot block,RgbLightVolume.Snapshot sky) {
        return new RegionFrame(protocol,session,epoch,sequence,dimension,slot,bounds,block,sky);
    }
    private RegionFrame(int protocol,UUID session,long epoch,long sequence,int dimension,int slot,
                        CapturedRgbRegion.Bounds bounds,RgbLightVolume.Snapshot block,RgbLightVolume.Snapshot sky) {
        if((protocol!=3 && protocol!=4) || epoch<=0 || sequence<=0 || slot<4 || slot>=MAX_STREAM_SLOTS)
            throw new IllegalArgumentException("Invalid snapshot frame header");
        Objects.requireNonNull(bounds);
        if(bounds.sectionsX()!=1 || bounds.sectionsZ()!=1 || block==null && sky==null)
            throw new IllegalArgumentException("Invalid snapshot column lanes or bounds");
        validateRoot(bounds,block);validateRoot(bounds,sky);
        this.protocol=protocol;kind=Kind.UPSERT;this.session=Objects.requireNonNull(session);this.epoch=epoch;
        this.sequence=sequence;this.dimension=dimension;this.slot=slot;profile=null;this.bounds=bounds;
        colors=skyColors=null;blockPresent=block!=null;skyPresent=sky!=null;
        sections=new Section[bounds.sectionsY()];
        for(int y=0;y<sections.length;y++)sections[y]=new Section(block==null?null:Lane.snapshot(block.section(y)),
                sky==null?null:Lane.snapshot(sky.section(y)));
    }
    private static void validateRoot(CapturedRgbRegion.Bounds bounds,RgbLightVolume.Snapshot root) {
        if(root!=null && (!root.isColumn() || root.cellCount()!=bounds.outputCells() || root.sectionCount()!=bounds.sectionsY()))
            throw new IllegalArgumentException("Snapshot column shape changed");
    }
    /** Keep unchanged section objects and publish all changed lanes in one new frame. */
    RegionFrame patched(long nextSequence,Section[] nextSections) {
        return new RegionFrame(this,nextSequence,nextSections);
    }
    private RegionFrame(RegionFrame base,long sequence,Section[] sections) {
        if(base.protocol!=4 || base.kind!=Kind.UPSERT || sequence<=base.sequence || sections.length!=base.sections.length)
            throw new IllegalArgumentException("Invalid patched frame");
        protocol=4;kind=Kind.UPSERT;session=base.session;epoch=base.epoch;this.sequence=sequence;
        dimension=base.dimension;slot=base.slot;profile=null;bounds=base.bounds;
        colors=skyColors=null;blockPresent=base.blockPresent;skyPresent=base.skyPresent;
        this.sections=sections.clone();for(var section:this.sections)Objects.requireNonNull(section);
    }
    public static RegionFrame helloV4(UUID session,long epoch,int dimension,String profile) {return new RegionFrame(4,Kind.HELLO,session,epoch,0,dimension,-1,profile,null,null,null);}
    public static RegionFrame upsertV4(UUID session,long epoch,long sequence,int dimension,int slot,CapturedRgbRegion.Bounds bounds,short[] block,short[] sky) {return new RegionFrame(4,Kind.UPSERT,session,epoch,sequence,dimension,slot,null,bounds,block,sky);}
    public static RegionFrame removeV4(UUID session,long epoch,long sequence,int dimension,int slot) {return new RegionFrame(4,Kind.REMOVE,session,epoch,sequence,dimension,slot,null,null,null,null);}
    private static void validateColors(short[] colors,int cells) {
        if(colors==null) return;
        if(colors.length!=cells) throw new IllegalArgumentException("Invalid lane cell count");
        for(short color:colors) PackedRgb.validate(color);
    }
    public static RegionFrame helloV3(UUID session,long epoch,int dimension,String profile) {return new RegionFrame(3,Kind.HELLO,session,epoch,0,dimension,-1,profile,null,null,null);}
    public static RegionFrame upsertV3(UUID session,long epoch,long sequence,int dimension,int slot,CapturedRgbRegion.Bounds bounds,short[] block,short[] sky) {return new RegionFrame(3,Kind.UPSERT,session,epoch,sequence,dimension,slot,null,bounds,block,sky);}
    public static RegionFrame removeV3(UUID session,long epoch,long sequence,int dimension,int slot) {return new RegionFrame(3,Kind.REMOVE,session,epoch,sequence,dimension,slot,null,null,null,null);}
    public static RegionFrame helloV2(UUID session,long epoch,int dimension,String profile) {
        return new RegionFrame(2,Kind.HELLO,session,epoch,0,dimension,-1,profile,null,null,null);
    }
    public static RegionFrame upsertV2(UUID session,long epoch,long sequence,int dimension,int slot,CapturedRgbRegion.Bounds bounds,short[] block,short[] sky) {
        return new RegionFrame(2,Kind.UPSERT,session,epoch,sequence,dimension,slot,null,bounds,block,sky);
    }
    public static RegionFrame removeV2(UUID session,long epoch,long sequence,int dimension,int slot) {
        return new RegionFrame(2,Kind.REMOVE,session,epoch,sequence,dimension,slot,null,null,null,null);
    }
    public static RegionFrame hello(UUID session,long epoch,int dimension,String profile) {
        return new RegionFrame(Kind.HELLO,session,epoch,0,dimension,-1,profile,null,null);
    }
    public static RegionFrame upsert(UUID session,long epoch,long sequence,int dimension,int slot,CapturedRgbRegion.Bounds bounds,short[] colors) {
        return new RegionFrame(Kind.UPSERT,session,epoch,sequence,dimension,slot,null,bounds,colors);
    }
    public static RegionFrame remove(UUID session,long epoch,long sequence,int dimension,int slot) {
        return new RegionFrame(Kind.REMOVE,session,epoch,sequence,dimension,slot,null,null,null);
    }
    public int color(int cell) { return colors!=null ? colors[cell] : sectionForCell(cell).block(localCell(cell)); }
    public int skyColor(int cell) { return skyColors!=null ? skyColors[cell] : sectionForCell(cell).sky(localCell(cell)); }
    public boolean hasBlock() { return blockPresent; }
    public boolean hasSky() { return skyPresent; }
    private Section sectionForCell(int cell) {
        if(cell<0 || cell>=cells())throw new IndexOutOfBoundsException("Outside frame");
        if(bounds.sectionsX()==1 && bounds.sectionsZ()==1)return sections[cell>>>12];
        return section((cell%bounds.sizeX())>>>4,(cell/(bounds.sizeX()*bounds.sizeZ()))>>>4,(cell/bounds.sizeX()%bounds.sizeZ())>>>4);
    }
    private int localCell(int cell) {if(bounds.sectionsX()==1 && bounds.sectionsZ()==1)return cell&4095;return (cell%bounds.sizeX()&15)|(cell/bounds.sizeX()%bounds.sizeZ()&15)<<4|(cell/(bounds.sizeX()*bounds.sizeZ())&15)<<8;}
    public int cells() { return bounds==null ? 0 : bounds.outputCells(); }
    /** Immutable section view over owned arrays or retained publication data. */
    public Section section(int x,int y,int z) {
        if(bounds==null || x<0 || y<0 || z<0 || x>=bounds.sectionsX() || y>=bounds.sectionsY() || z>=bounds.sectionsZ())
            throw new IndexOutOfBoundsException("Outside RGB frame sections");
        return sections[x+bounds.sectionsX()*(z+bounds.sectionsZ()*y)];
    }
    /** Columns have the same contiguous section order as the global wire cell order. */
    void writeColumnLane(boolean skyLane,ByteBuf out) {
        if(slot<4 || bounds.sectionsX()!=1 || bounds.sectionsZ()!=1)
            throw new IllegalStateException("Bulk full encoding requires an automatic column");
        for(var section:sections)(skyLane?section.sky:section.block).writeTo(out);
    }
    /** Non-null means present; a null snapshot inside a lane represents present zero light. */
    static final class Lane {
        private final short[] values;
        private final RgbSection.Snapshot snapshot;
        private final int offset,rowStride,layerStride;
        private Lane(short[] values,RgbSection.Snapshot snapshot,int offset,int rowStride,int layerStride) {
            this.values=values;this.snapshot=snapshot;this.offset=offset;this.rowStride=rowStride;this.layerStride=layerStride;
        }
        static Lane snapshot(RgbSection.Snapshot section) {return new Lane(null,section,0,16,256);}
        static Lane copy(short[] values) {
            if(values.length!=4096)throw new IllegalArgumentException("Invalid section payload");
            short[] owned=values.clone();for(short value:owned)PackedRgb.validate(value);
            return new Lane(owned,null,0,16,256);
        }
        int get(int cell) {
            Objects.checkIndex(cell,4096);
            return values!=null?values[offset+(cell&15)+rowStride*(cell>>>4&15)+layerStride*(cell>>>8)]
                    :snapshot==null?0:snapshot.get(cell);
        }
        void writeTo(ByteBuf out) {
            if(values!=null) {
                if(rowStride==16 && layerStride==256) {
                    for(int index=offset,end=offset+4096;index<end;index++)out.writeShort(values[index]);
                } else {
                    for(int y=0;y<16;y++)for(int z=0;z<16;z++) {
                        int row=offset+layerStride*y+rowStride*z;
                        for(int x=0;x<16;x++)out.writeShort(values[row+x]);
                    }
                }
            } else if(snapshot==null || snapshot.contentEquals(null))out.writeZero(8192);
            else snapshot.forEach(out::writeShort);
        }
    }
    public static final class Section {
        private final Lane block,sky;
        private Section(short[] block,short[] sky,int offset,int rowStride,int layerStride) {
            this(block==null?null:new Lane(block,null,offset,rowStride,layerStride),
                 sky==null?null:new Lane(sky,null,offset,rowStride,layerStride));
        }
        private Section(Lane block,Lane sky) {this.block=block;this.sky=sky;}
        public int block(int cell) {Objects.checkIndex(cell,4096);return block==null?-1:block.get(cell);}
        public int sky(int cell) {Objects.checkIndex(cell,4096);return sky==null?-1:sky.get(cell);}
        Section replace(short[] nextBlock,short[] nextSky) {
            return replace(nextBlock==null?null:Lane.copy(nextBlock),nextSky==null?null:Lane.copy(nextSky));
        }
        Section replace(Lane nextBlock,Lane nextSky) {
            return new Section(nextBlock==null?block:nextBlock,nextSky==null?sky:nextSky);
        }
    }
    public int sample(int x,int y,int z) {
        return sample(x,y,z,false);
    }
    public int sampleSky(int x,int y,int z) { return sample(x,y,z,true); }
    public boolean contains(int x,int y,int z) {
        return bounds!=null && x>=bounds.x() && x<bounds.x()+bounds.sizeX() && y>=bounds.y() && y<bounds.y()+bounds.sizeY()
                && z>=bounds.z() && z<bounds.z()+bounds.sizeZ();
    }
    private int sample(int x,int y,int z,boolean sky) {
        if(!(sky?hasSky():hasBlock()) || !contains(x,y,z)) return -1;
        int cell=x-bounds.x()+bounds.sizeX()*(z-bounds.z()+bounds.sizeZ()*(y-bounds.y()));
        return sky?skyColor(cell):color(cell);
    }
}

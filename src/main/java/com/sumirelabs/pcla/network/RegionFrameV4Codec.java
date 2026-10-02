package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import io.netty.buffer.ByteBuf;
import java.util.UUID;

/** V4 keeps bounded full frames and adds kind=3, a paired section delta. */
public final class RegionFrameV4Codec {
    public static final int MAX_BYTES=262212;
    public record Packet(RegionFrame frame,SectionDelta delta) {
        public Packet {if((frame==null)==(delta==null) || frame!=null && frame.protocol!=4)throw new IllegalArgumentException("Exactly one V4 payload required");}
        public static Packet full(RegionFrame frame){return new Packet(frame,null);}
        public static Packet patch(SectionDelta delta){return new Packet(null,delta);}
    }
    public static void encode(Packet packet,ByteBuf out) {
        if(packet.frame()!=null){RegionFrameV3Codec.encodeVersion(packet.frame(),out,4);return;}
        var d=packet.delta();out.writeByte(4);out.writeByte(3);
        out.writeLong(d.session.getMostSignificantBits());out.writeLong(d.session.getLeastSignificantBits());
        out.writeLong(d.epoch);out.writeLong(d.sequence);out.writeInt(d.dimension);out.writeShort(d.slot);
        var b=d.bounds;out.writeInt(b.x());out.writeInt(b.y());out.writeInt(b.z());
        out.writeByte(b.sectionsX());out.writeByte(b.sectionsY());out.writeByte(b.sectionsZ());out.writeByte(d.lanes);
        out.writeLong(d.baseSequence);out.writeShort(d.blockMask);out.writeShort(d.skyMask);
        for(boolean sky:new boolean[]{false,true})for(int y=0;y<b.sectionsY();y++)if(((sky?d.skyMask:d.blockMask)&(1<<y))!=0)
            d.writeSection(sky,y,out);
    }
    public static Packet decode(ByteBuf in) {
        if(in.readableBytes()<38 || in.readableBytes()>MAX_BYTES || in.getUnsignedByte(in.readerIndex())!=4)
            throw new IllegalArgumentException("Invalid V4 packet");
        if(in.getUnsignedByte(in.readerIndex()+1)!=3)return Packet.full(RegionFrameV3Codec.decodeVersion(in,4));
        if(in.readableBytes()<68)throw new IllegalArgumentException("Truncated delta header");
        in.skipBytes(2);var session=new UUID(in.readLong(),in.readLong());long epoch=in.readLong(),sequence=in.readLong();
        int dimension=in.readInt(),slot=in.readUnsignedShort();
        var bounds=new CapturedRgbRegion.Bounds(in.readInt(),in.readInt(),in.readInt(),in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedByte());
        int lanes=in.readUnsignedByte();long base=in.readLong();int blockMask=in.readUnsignedShort(),skyMask=in.readUnsignedShort();
        int validMask=(1<<bounds.sectionsY())-1;
        if(slot<4 || slot>=256 || bounds.sectionsX()!=1 || bounds.sectionsZ()!=1 || lanes<1 || lanes>3
                || ((blockMask|skyMask)&~validMask)!=0 || (blockMask|skyMask)==0
                || blockMask!=0 && (lanes&1)==0 || skyMask!=0 && (lanes&2)==0
                || epoch<=0 || base<=0 || sequence<=base
                || in.readableBytes()!=8192*(Integer.bitCount(blockMask)+Integer.bitCount(skyMask)))
            throw new IllegalArgumentException("Invalid delta masks or length");
        short[][] block=readLane(in,bounds.sectionsY(),blockMask),sky=readLane(in,bounds.sectionsY(),skyMask);
        return Packet.patch(new SectionDelta(session,epoch,sequence,base,dimension,slot,bounds,lanes,block,sky));
    }
    private static short[][] readLane(ByteBuf in,int sections,int mask) {
        short[][] values=new short[sections][];
        for(int y=0;y<sections;y++)if((mask&(1<<y))!=0){values[y]=new short[4096];for(int cell=0;cell<4096;cell++)values[y][cell]=in.readShort();}
        return values;
    }
    private RegionFrameV4Codec() {}
}

package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** One bounded replacement contains every enabled lane, so client publication is atomic. */
public final class RegionFrameV2Codec {
    public static final int VERSION=2,MAX_BYTES=132000;
    private RegionFrameV2Codec() {}
    public static void encode(RegionFrame frame,ByteBuf out) {
        if(frame.protocol!=VERSION) throw new IllegalArgumentException("Wrong RGB protocol encoder");
        out.writeByte(VERSION); out.writeByte(frame.kind.ordinal());
        out.writeLong(frame.session.getMostSignificantBits()); out.writeLong(frame.session.getLeastSignificantBits());
        out.writeLong(frame.epoch); out.writeLong(frame.sequence); out.writeInt(frame.dimension);
        if(frame.kind==RegionFrame.Kind.HELLO) out.writeBytes(frame.profile.getBytes(StandardCharsets.US_ASCII));
        else {
            out.writeByte(frame.slot);
            if(frame.kind==RegionFrame.Kind.UPSERT) {
                var b=frame.bounds; out.writeInt(b.x()); out.writeInt(b.y()); out.writeInt(b.z());
                out.writeByte(b.sectionsX()); out.writeByte(b.sectionsY()); out.writeByte(b.sectionsZ());
                out.writeByte((frame.hasBlock()?1:0)|(frame.hasSky()?2:0));
                if(frame.hasBlock()) for(int cell=0;cell<frame.cells();cell++) out.writeShort(frame.color(cell));
                if(frame.hasSky()) for(int cell=0;cell<frame.cells();cell++) out.writeShort(frame.skyColor(cell));
            }
        }
    }
    public static RegionFrame decode(ByteBuf in) {
        if(in.readableBytes()<38 || in.readableBytes()>MAX_BYTES) throw new IllegalArgumentException("Invalid RGB packet length");
        if(in.readUnsignedByte()!=VERSION) throw new IllegalArgumentException("Unsupported RGB protocol");
        int kind=in.readUnsignedByte(); var session=new UUID(in.readLong(),in.readLong());
        long epoch=in.readLong(),sequence=in.readLong(); int dimension=in.readInt(); RegionFrame result;
        if(kind==0) {
            if(in.readableBytes()!=64 || sequence!=0) throw new IllegalArgumentException("Invalid hello payload");
            result=RegionFrame.helloV2(session,epoch,dimension,in.readCharSequence(64,StandardCharsets.US_ASCII).toString());
        } else if(kind==1 || kind==2) {
            if(in.readableBytes()<(kind==1?17:1)) throw new IllegalArgumentException("Truncated region header");
            int slot=in.readUnsignedByte();
            if(kind==2) result=RegionFrame.removeV2(session,epoch,sequence,dimension,slot);
            else {
                var b=new CapturedRgbRegion.Bounds(in.readInt(),in.readInt(),in.readInt(),in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedByte());
                if(b.sectionsY()>2) throw new IllegalArgumentException("Invalid legacy region height");
                int lanes=in.readUnsignedByte();
                if(lanes<1 || lanes>3 || in.readableBytes()!=b.outputCells()*2*Integer.bitCount(lanes)) throw new IllegalArgumentException("Invalid RGB lane payload");
                short[] block=(lanes&1)!=0 ? new short[b.outputCells()] : null,sky=(lanes&2)!=0 ? new short[b.outputCells()] : null;
                if(block!=null) for(int cell=0;cell<block.length;cell++) block[cell]=in.readShort();
                if(sky!=null) for(int cell=0;cell<sky.length;cell++) sky[cell]=in.readShort();
                result=RegionFrame.upsertV2(session,epoch,sequence,dimension,slot,b,block,sky);
            }
        } else throw new IllegalArgumentException("Unknown RGB message kind");
        if(in.isReadable()) throw new IllegalArgumentException("Trailing RGB packet bytes");
        return result;
    }
}

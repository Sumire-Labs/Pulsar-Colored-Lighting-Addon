package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.world.CapturedRgbRegion;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Fixed caps are checked before payload allocation; trailing bytes and unknown schemas are rejected. */
public final class RegionFrameCodec {
    public static final int VERSION=1, MAX_BYTES=66000;
    private RegionFrameCodec() {}
    public static void encode(RegionFrame frame,ByteBuf out) {
        if(frame.protocol!=VERSION) throw new IllegalArgumentException("Wrong RGB protocol encoder");
        out.writeByte(VERSION); out.writeByte(frame.kind.ordinal());
        out.writeLong(frame.session.getMostSignificantBits()); out.writeLong(frame.session.getLeastSignificantBits());
        out.writeLong(frame.epoch); out.writeLong(frame.sequence); out.writeInt(frame.dimension);
        if(frame.kind==RegionFrame.Kind.HELLO) out.writeBytes(frame.profile.getBytes(StandardCharsets.US_ASCII));
        else {
            out.writeByte(frame.slot);
            if(frame.kind==RegionFrame.Kind.UPSERT) {
                var b=frame.bounds;
                out.writeInt(b.x()); out.writeInt(b.y()); out.writeInt(b.z());
                out.writeByte(b.sectionsX()); out.writeByte(b.sectionsY()); out.writeByte(b.sectionsZ());
                for(int cell=0;cell<frame.cells();cell++) out.writeShort(frame.color(cell));
            }
        }
    }
    public static RegionFrame decode(ByteBuf in) {
        if(in.readableBytes()<38 || in.readableBytes()>MAX_BYTES) throw new IllegalArgumentException("Invalid RGB packet length");
        if(in.readUnsignedByte()!=VERSION) throw new IllegalArgumentException("Unsupported RGB protocol");
        int kind=in.readUnsignedByte();
        UUID session=new UUID(in.readLong(),in.readLong());
        long epoch=in.readLong(),sequence=in.readLong(); int dimension=in.readInt();
        RegionFrame result;
        if(kind==0) {
            if(in.readableBytes()!=64 || sequence!=0) throw new IllegalArgumentException("Invalid hello payload");
            result=RegionFrame.hello(session,epoch,dimension,in.readCharSequence(64,StandardCharsets.US_ASCII).toString());
        } else if(kind==1 || kind==2) {
            int slot=in.readUnsignedByte();
            if(kind==2) result=RegionFrame.remove(session,epoch,sequence,dimension,slot);
            else {
                var bounds=new CapturedRgbRegion.Bounds(in.readInt(),in.readInt(),in.readInt(),in.readUnsignedByte(),in.readUnsignedByte(),in.readUnsignedByte());
                if(bounds.sectionsY()>2) throw new IllegalArgumentException("Invalid legacy region height");
                if(in.readableBytes()!=bounds.outputCells()*2) throw new IllegalArgumentException("Invalid RGB cell count");
                short[] colors=new short[bounds.outputCells()];
                for(int cell=0;cell<colors.length;cell++) colors[cell]=in.readShort();
                result=RegionFrame.upsert(session,epoch,sequence,dimension,slot,bounds,colors);
            }
        } else throw new IllegalArgumentException("Unknown RGB message kind");
        if(in.isReadable()) throw new IllegalArgumentException("Trailing RGB packet bytes");
        return result;
    }
}

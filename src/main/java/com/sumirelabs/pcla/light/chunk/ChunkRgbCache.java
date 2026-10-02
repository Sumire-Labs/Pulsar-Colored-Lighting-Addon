package com.sumirelabs.pcla.light.chunk;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;

/** Bounded, checksummed cache codec. A cache is only a hint; invalid data takes the cold path. */
final class ChunkRgbCache {
    private static final int MAGIC=0x50434c41,VERSION=1,MAX_PROFILE_BYTES=1024;
    record Data(short[] block,short[] sky) {}
    static MessageDigest digest() {
        try{return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    static byte[] encode(int minY,int maxY,boolean hasSky,String profile,byte[] opticalHash,short[] block,short[] sky) {
        byte[] profileBytes=profile.getBytes(StandardCharsets.UTF_8);
        if(profileBytes.length>MAX_PROFILE_BYTES)throw new IllegalArgumentException("Profile identity is too long");
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(minY);out.writeInt(maxY);out.writeBoolean(hasSky);
            out.writeShort(profileBytes.length);out.write(profileBytes);out.write(opticalHash);out.writeInt(block.length);
            for(short v:block)out.writeShort(v);
            if(hasSky)for(short v:sky)out.writeShort(v);
            out.flush();byte[] payload=bytes.toByteArray();out.write(digest().digest(payload));out.flush();return bytes.toByteArray();
        } catch(IOException impossible){throw new AssertionError(impossible);}
    }
    static Data decode(byte[] bytes,int minY,int maxY,boolean hasSky,String profile,byte[] opticalHash,int cells) {
        if(bytes==null || bytes.length<87 || bytes.length>87L+MAX_PROFILE_BYTES+(hasSky?4L:2L)*cells)return null;
        try {
            int payloadLength=bytes.length-32;MessageDigest checksum=digest();checksum.update(bytes,0,payloadLength);
            if(!MessageDigest.isEqual(checksum.digest(),Arrays.copyOfRange(bytes,payloadLength,bytes.length)))return null;
            var in=new DataInputStream(new ByteArrayInputStream(bytes,0,payloadLength));
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION || in.readInt()!=minY || in.readInt()!=maxY || in.readBoolean()!=hasSky)return null;
            int length=in.readUnsignedShort();if(length>MAX_PROFILE_BYTES)return null;
            if(!Arrays.equals(in.readNBytes(length),profile.getBytes(StandardCharsets.UTF_8)))return null;
            if(!MessageDigest.isEqual(in.readNBytes(32),opticalHash) || in.readInt()!=cells)return null;
            if(in.available()!=(hasSky?4:2)*cells)return null;
            short[] block=new short[cells],sky=hasSky?new short[cells]:null;
            for(int i=0;i<cells;i++){block[i]=in.readShort();if((block[i]&~4095)!=0)return null;}
            if(hasSky)for(int i=0;i<cells;i++){sky[i]=in.readShort();if((sky[i]&~4095)!=0)return null;}
            return new Data(block,sky);
        } catch(IOException|RuntimeException malformed){return null;}
    }
}

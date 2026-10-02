package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.Reference;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Optional acceleration data only. Invalid/truncated cache always falls back to fresh lighting. */
@Mod.EventBusSubscriber(modid=Reference.MOD_ID)
public final class RgbChunkPersistence {
    private static final String TAG="PclaRgbCacheV1";
    private static final int MAX_COMPRESSED=1_048_576,MAX_DECODED=2_097_152;
    private static final boolean ENABLED=Boolean.parseBoolean(System.getProperty("pcla.rgbCache","true"));
    // Chunk identity prevents coordinate reuse across worlds/unloads. Values contain no chunk reference.
    private static final Map<Chunk,byte[]> CACHE=Collections.synchronizedMap(new WeakHashMap<>());
    private RgbChunkPersistence() {}

    @SubscribeEvent public static void load(ChunkDataEvent.Load event) {
        if(!ENABLED||event.getWorld().isRemote)return;
        byte[] bytes=event.getData().getByteArray(TAG);
        if(bytes.length>0&&bytes.length<=MAX_COMPRESSED)CACHE.put(event.getChunk(),bytes);
    }
    @SubscribeEvent public static void save(ChunkDataEvent.Save event) {
        if(!ENABLED||event.getWorld().isRemote)return;
        byte[] bytes=CACHE.get(event.getChunk());
        if(bytes!=null)event.getData().setByteArray(TAG,bytes);
    }
    static byte[] read(Chunk chunk) {
        if(!ENABLED)return null;
        byte[] bytes=CACHE.get(chunk);if(bytes==null)return null;
        try(var input=new InflaterInputStream(new ByteArrayInputStream(bytes));var output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1){if(output.size()+count>MAX_DECODED)return null;output.write(buffer,0,count);}
            return output.toByteArray();
        } catch(IOException|RuntimeException invalid){return null;}
    }
    static void store(Chunk chunk,byte[] payload) {
        if(!ENABLED||payload==null||payload.length>MAX_DECODED)return;
        try(var output=new ByteArrayOutputStream()) {
            try(var compressed=new DeflaterOutputStream(output)){compressed.write(payload);}
            byte[] bytes=output.toByteArray();
            if(bytes.length<=MAX_COMPRESSED&&!Arrays.equals(bytes,CACHE.get(chunk))) {
                CACHE.put(chunk,bytes);
                var server=chunk.getWorld().getMinecraftServer();
                if(server!=null)server.addScheduledTask(chunk::markDirty);
            }
        } catch(IOException invalid){/* A cache write cannot fail normal world lighting or chunk saving. */}
    }
}

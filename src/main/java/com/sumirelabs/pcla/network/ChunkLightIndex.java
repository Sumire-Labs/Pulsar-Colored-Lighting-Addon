package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.light.world.RegionPublication;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Immutable chunk/section index, built before publication to rendering threads. */
public final class ChunkLightIndex {
    private final Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<RegionFrame.Section>> chunks=new Long2ObjectOpenHashMap<>();
    private final int sectionCount;
    public ChunkLightIndex(Map<Integer,RegionFrame> regions) {
        int count=0;
        // Both lanes use the same lowest-slot owner; a missing lane must not fall through to another region.
        for(var frame:new TreeMap<>(regions).values()) {
            if(frame.kind!=RegionFrame.Kind.UPSERT) continue;
            var b=frame.bounds;
            for(int z=0;z<b.sectionsZ();z++) for(int x=0;x<b.sectionsX();x++) {
                long key=RegionPublication.key((b.x()>>4)+x,(b.z()>>4)+z);
                var column=chunks.get(key);
                if(column==null) { column=new Int2ObjectOpenHashMap<>(); chunks.put(key,column); }
                for(int y=0;y<b.sectionsY();y++) {
                    int sectionY=(b.y()>>4)+y;
                    if(!column.containsKey(sectionY)) { column.put(sectionY,frame.section(x,y,z)); count++; }
                }
            }
        }
        sectionCount=count;
    }
    public RegionFrame.Section section(int chunkX,int sectionY,int chunkZ) {
        var column=chunks.get(RegionPublication.key(chunkX,chunkZ));
        return column==null?null:column.get(sectionY);
    }
    public int block(int x,int y,int z) {
        var section=section(x>>4,y>>4,z>>4);
        return section==null?-1:section.block((x&15)|(z&15)<<4|(y&15)<<8);
    }
    public int sky(int x,int y,int z) {
        var section=section(x>>4,y>>4,z>>4);
        return section==null?-1:section.sky((x&15)|(z&15)<<4|(y&15)<<8);
    }
    public int chunks() { return chunks.size(); }
    public int sections() { return sectionCount; }
}

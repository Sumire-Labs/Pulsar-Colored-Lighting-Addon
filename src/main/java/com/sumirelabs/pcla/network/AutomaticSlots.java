package com.sumirelabs.pcla.network;

import java.util.*;

/** Per-stream bounded slot assignment. A retired slot is removed before it can be reused. */
public final class AutomaticSlots {
    public record Removed(long chunk,int slot) {}
    private final Map<Long,Integer> slots=new LinkedHashMap<>();
    public List<Removed> update(Set<Long> desired) {
        if(desired.size()>RegionFrame.MAX_STREAM_SLOTS-4)throw new IllegalArgumentException("Too many client columns");
        var removed=new ArrayList<Removed>();var iterator=slots.entrySet().iterator();
        while(iterator.hasNext()){var entry=iterator.next();if(!desired.contains(entry.getKey())){removed.add(new Removed(entry.getKey(),entry.getValue()));iterator.remove();}}
        var used=new BitSet(RegionFrame.MAX_STREAM_SLOTS);used.set(0,4);slots.values().forEach(used::set);
        for(long key:desired)if(!slots.containsKey(key)){int slot=used.nextClearBit(4);slots.put(key,slot);used.set(slot);}
        return List.copyOf(removed);
    }
    public int slot(long chunk){Integer result=slots.get(chunk);if(result==null)throw new IllegalArgumentException("Unassigned column");return result;}
    public Set<Long> chunks(){return Set.copyOf(slots.keySet());}
}

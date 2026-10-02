package com.sumirelabs.pcla.light.world;

import java.util.*;

/** Near-first desired columns and bounded, round-robin admission across players. No chunk loading. */
public final class AutomaticInterest {
    public static final int WORLD_LIMIT=256;
    public record Allocation<K>(Set<Long> columns,Map<K,Set<Long>> players) {}
    public static List<Long> around(int x,int z,int radius) {
        if(radius<0 || radius>4)throw new IllegalArgumentException("Invalid automatic radius");
        var offsets=new ArrayList<int[]>();for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++)offsets.add(new int[]{dx,dz});
        offsets.sort(Comparator.<int[]>comparingInt(p->p[0]*p[0]+p[1]*p[1]).thenComparingInt(p->p[1]).thenComparingInt(p->p[0]));
        var result=new ArrayList<Long>();for(var p:offsets)result.add(RegionPublication.key(Math.addExact(x,p[0]),Math.addExact(z,p[1])));
        return List.copyOf(result);
    }
    public static <K> Allocation<K> allocate(Map<K,List<Long>> desired,int limit) {
        if(limit<1)throw new IllegalArgumentException("Positive interest limit required");
        var admitted=new LinkedHashSet<Long>();var iterators=new ArrayList<Iterator<Long>>();desired.values().forEach(list->iterators.add(list.iterator()));
        boolean progress=true;
        while(admitted.size()<limit && progress) {
            progress=false;
            for(var iterator:iterators) {
                while(iterator.hasNext())if(admitted.add(iterator.next())){progress=true;break;}
                if(admitted.size()==limit)break;
            }
        }
        var perPlayer=new LinkedHashMap<K,Set<Long>>();desired.forEach((player,list)->{
            var columns=new LinkedHashSet<Long>();for(long key:list)if(admitted.contains(key))columns.add(key);
            perPlayer.put(player,Collections.unmodifiableSet(columns));
        });
        return new Allocation<>(Collections.unmodifiableSet(admitted),Collections.unmodifiableMap(perPlayer));
    }
    private AutomaticInterest() {}
}

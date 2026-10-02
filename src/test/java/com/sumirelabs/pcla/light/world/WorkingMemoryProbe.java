package com.sumirelabs.pcla.light.world;

import com.sumirelabs.pcla.light.engine.RgbBlockPropagator;
import com.sumirelabs.pcla.light.engine.RgbSkyPropagator;
import java.lang.reflect.*;
import java.util.*;

/** Retained primitive-array payload inventory, not total heap or an execution benchmark. */
public final class WorkingMemoryProbe {
    private static final class Inventory {
        final Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<String,Long> bytes=new TreeMap<>();
        void add(String category,long amount) {bytes.merge(category,amount,Long::sum);}
        void visit(Object value,String category) throws IllegalAccessException {
            if(value==null || !seen.add(value)) return;
            Class<?> type=value.getClass();
            if(type.isArray()) {
                Class<?> component=type.getComponentType();
                if(component.isPrimitive()) {
                    int size=component==long.class || component==double.class?8:component==int.class || component==float.class?4:component==short.class || component==char.class?2:1;
                    add(category,(long)Array.getLength(value)*size);
                } else for(Object child:(Object[])value) visit(child,category);
                return;
            }
            // BitSet.size(), unlike toLongArray(), includes cleared retained capacity.
            if(value instanceof BitSet bits) {add("bitmaps",bits.size()/8L);return;}
            if(value instanceof Map<?,?> map) {for(var entry:map.entrySet()){visit(entry.getKey(),category);visit(entry.getValue(),category);}return;}
            if(value instanceof Iterable<?> list) {for(Object child:list)visit(child,category);return;}
            if(!type.getName().startsWith("com.sumirelabs.pcla.light.")) return;
            if(type.getName().contains("RgbSection")) category="published_light";
            for(Field field:type.getDeclaredFields()) {
                if(Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())continue;
                field.setAccessible(true);String next=category;
                if(field.getName().equals("lights") && type.getSimpleName().startsWith("Captured")) next="working_light";
                if(field.getName().equals("indices"))next="coordinate_index";
                if(type.getSimpleName().equals("LongWorkQueue"))next="queues";
                if(type.getName().contains("SharedOpticalInputs$Page"))next="shared_optical";
                if(type.getSimpleName().equals("SkyColumnCache") && field.getName().equals("masks"))next="sky_masks";
                visit(field.get(value),next);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        System.out.println("owners,limit,working_light,coordinate_index,queues,published_light,bitmaps,sky_masks,shared_optical,total_array_payload,uniform_optical_pages");
        for(int side:new int[]{1,2,3}) {
            var bounds=new ArrayList<CapturedRgbRegion.Bounds>();var chunks=new HashMap<Long,Object>();
            for(int z=0;z<side;z++)for(int x=0;x<side;x++) {
                var b=new CapturedRgbRegion.Bounds(x*16,0,z*16,1,16,1);bounds.add(b);
                for(long key:b.chunks())chunks.putIfAbsent(key,new Object());
            }
            int limit=args.length==0?bounds.size():Integer.parseInt(args[0]);
            var store=new SharedOpticalInputs<Object>();var roots=new ArrayList<Object>();roots.add(store);
            var cache=new WorkingSet<CapturedRgbRegion.Bounds,List<Object>>(limit);roots.add(cache);
            SharedOpticalInputs.Reader<Object> reader=(chunk,x,y,z,out)->{
                out.source=x==8 && y==65 && z==8?0xf00:0;
                out.blockedFaces=y<64?63:0;out.contextual=false;
                Arrays.fill(out.incoming,y<64?0xfff:0);
            };
            for(var b:bounds) cache.acquire(b,()->{
                var input=new CapturedRgbRegion(b,true,0,255);
                var request=new SharedOpticalInputs.Request<>(b,chunks,input.planShared(new BitSet(),true));
                input.refreshShared(store.capture(List.of(request),reader).get(0),input.cellCount());
                var block=new RgbBlockPropagator(input);block.relight();input.interior();
                var skyInput=new CapturedSkyRegion(input,0,255);
                skyInput.refreshColumns((column,out)->{out.mask=0xfff;out.contextual=false;},new BitSet(),true);
                var sky=new RgbSkyPropagator(skyInput);sky.relight();skyInput.interior();
                if(skyInput.light(input.bounds.inputCell(input.bounds.x()+8,255,input.bounds.z()+8))!=0xfff)
                    throw new AssertionError("Full sky missing in inventory fixture");
                // Completed roots remain resident even when their mutable state is evicted.
                roots.add(input.interior());roots.add(skyInput.interior());
                return List.of(input,block,skyInput,sky);
            });
            var inventory=new Inventory();inventory.visit(roots,"other");
            long expectedWorking=4L*bounds.get(0).cells()*Math.min(limit,bounds.size());
            if(inventory.bytes.getOrDefault("working_light",0L)!=expectedWorking)throw new AssertionError("Working arrays were not counted once");
            if(inventory.bytes.getOrDefault("coordinate_index",0L)!=4L*bounds.get(0).cells())throw new AssertionError("Shared coordinate index was double counted");
            System.out.print(bounds.size()+","+limit);
            for(String group:List.of("working_light","coordinate_index","queues","published_light","bitmaps","sky_masks","shared_optical"))
                System.out.print(","+inventory.bytes.getOrDefault(group,0L));
            System.out.println(","+inventory.bytes.values().stream().mapToLong(Long::longValue).sum()+","+store.stats().uniformPages());
        }
    }
}

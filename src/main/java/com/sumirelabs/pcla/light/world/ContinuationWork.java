package com.sumirelabs.pcla.light.world;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Identity FIFO of owners, with no captured worlds/tickets retained across continuation turns. */
public final class ContinuationWork<K> {
    public record Slice(int visited,int remaining,long elapsedNanos) {}
    private static final class Node<K> {final K key;Node<K> previous,next;Node(K key){this.key=key;}}
    private final IdentityHashMap<K,Node<K>> queued=new IdentityHashMap<>();
    private Node<K> first,last;
    private boolean closed;
    public synchronized void offer(K key) {
        Objects.requireNonNull(key);if(closed || queued.containsKey(key))return;
        var node=new Node<>(key);queued.put(key,node);node.previous=last;
        if(last==null)first=node;else last.next=node;last=node;
    }
    public synchronized void remove(K key) {var node=queued.remove(key);if(node!=null)unlink(node);}
    public synchronized void retain(Collection<? extends K> active) {
        Set<K> allowed=Collections.newSetFromMap(new IdentityHashMap<>());allowed.addAll(active);
        for(var node=first;node!=null;){var next=node.next;if(!allowed.contains(node.key))remove(node.key);node=next;}
    }
    public synchronized int size() {return queued.size();}
    public synchronized void close() {closed=true;queued.clear();first=last=null;}
    public synchronized K poll() {
        if(first==null)return null;K key=first.key;remove(key);return key;
    }
    private void unlink(Node<K> node) {
        if(node.previous==null)first=node.next;else node.previous.next=node.next;
        if(node.next==null)last=node.previous;else node.next.previous=node.previous;
        node.previous=node.next=null;
    }
    /** One owner always makes progress. The time budget is checked BETWEEN complete owner jobs. */
    public Slice drain(int maximum,long budgetNanos,LongSupplier clock,Consumer<K> action) {
        if(maximum<1 || budgetNanos<0)throw new IllegalArgumentException("Invalid continuation budget");
        long start=clock.getAsLong();int visited=0;
        while(visited<maximum) {
            K key=poll();if(key==null)break;
            action.accept(key);visited++;
            if(clock.getAsLong()-start>=budgetNanos)break;
        }
        return new Slice(visited,size(),clock.getAsLong()-start);
    }
}

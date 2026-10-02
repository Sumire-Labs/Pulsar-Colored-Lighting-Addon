package com.sumirelabs.pcla.light.world;

import java.util.*;
import java.util.function.Supplier;

/** Single-worker identity LRU. Completed results belong to the publication gates, not this cache. */
public final class WorkingSet<K,V> {
    public record Access<V>(V value,boolean created) {}
    public record Stats(int limit,int retained,long hits,long misses,long evictions,long creations) {}
    private static final class Node<K,V> {
        final K key; final V value; Node<K,V> previous,next;
        Node(K key,V value) {this.key=key;this.value=value;}
    }
    private final int limit;
    private final IdentityHashMap<K,Node<K,V>> entries=new IdentityHashMap<>();
    private Node<K,V> oldest,newest;
    private long hits,misses,evictions,creations;
    public WorkingSet(int limit) {
        if(limit<1 || limit>64)throw new IllegalArgumentException("RGB working-owner limit must be between 1 and 64");
        this.limit=limit;
    }
    public int limit() {return limit;}
    public boolean contains(K key){return entries.containsKey(key);}
    public Access<V> acquire(K key,Supplier<V> factory) {
        Objects.requireNonNull(key);Objects.requireNonNull(factory);
        var node=entries.get(key);
        if(node!=null) {hits++;unlink(node);append(node);return new Access<>(node.value,false);}
        misses++;
        // Release the cache reference BEFORE constructing another large working state.
        if(entries.size()==limit) {remove(oldest.key);evictions++;}
        V value=Objects.requireNonNull(factory.get());
        node=new Node<>(key,value);entries.put(key,node);append(node);creations++;
        return new Access<>(value,true);
    }
    public void remove(K key) {var node=entries.remove(key);if(node!=null)unlink(node);}
    public void retain(Collection<? extends K> active) {
        Set<K> allowed=Collections.newSetFromMap(new IdentityHashMap<>());allowed.addAll(active);
        for(var node=oldest;node!=null;) {var next=node.next;if(!allowed.contains(node.key))remove(node.key);node=next;}
    }
    public Stats stats() {return new Stats(limit,entries.size(),hits,misses,evictions,creations);}
    private void unlink(Node<K,V> node) {
        if(node.previous==null)oldest=node.next;else node.previous.next=node.next;
        if(node.next==null)newest=node.previous;else node.next.previous=node.previous;
        node.previous=node.next=null;
    }
    private void append(Node<K,V> node) {
        node.previous=newest;
        if(newest==null)oldest=node;else newest.next=node;
        newest=node;
    }
}

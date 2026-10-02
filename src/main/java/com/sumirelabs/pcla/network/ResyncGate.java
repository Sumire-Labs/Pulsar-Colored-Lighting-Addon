package com.sumirelabs.pcla.network;

/** Coalesce retries but retain a request received during the per-slot cooldown. */
public final class ResyncGate {
    private final boolean[] pending;
    private final long[] nextTick;
    public ResyncGate(int slots){pending=new boolean[slots];nextTick=new long[slots];}
    public boolean request(int slot){boolean added=!pending[slot];pending[slot]=true;return added;}
    public boolean pending(int slot){return pending[slot];}
    public boolean ready(int slot,long tick){return pending[slot] && tick>=nextTick[slot];}
    public void sent(int slot,long tick){if(pending[slot])nextTick[slot]=tick+20;pending[slot]=false;}
    public void retire(int slot){pending[slot]=false;nextTick[slot]=0;}
}

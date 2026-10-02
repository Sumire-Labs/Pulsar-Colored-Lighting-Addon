package com.sumirelabs.pcla.client;

/** Logical color and non-cumulative tint scopes, independent of OpenGL for verification. */
public final class TintScopeState {
    public record Tint(float red,float green,float blue) {
        public static final Tint WHITE=new Tint(1,1,1);
        public Tint {
            if(!valid(red)||!valid(green)||!valid(blue)) throw new IllegalArgumentException("Invalid tint");
        }
        private static boolean valid(float value) { return Float.isFinite(value)&&value>=0&&value<=1; }
    }
    @FunctionalInterface public interface Sink { void color(float r,float g,float b,float a); }
    private Tint tint=Tint.WHITE;
    private Object owner;
    private float red=1,green=1,blue=1,alpha=1;
    private Scope top;
    public Object owner() { return owner; }
    public Tint tint() { return tint; }
    public int depth() { int depth=0; for(var s=top;s!=null;s=s.previous) depth++; return depth; }
    public void replaceTint(Tint value) { tint=java.util.Objects.requireNonNull(value); }
    public void color(float r,float g,float b,float a,Sink original) {
        original.color(red(r),green(g),blue(b),alpha(a));
    }
    public float red(float value) { red=value; return value*tint.red; }
    public float green(float value) { green=value; return value*tint.green; }
    public float blue(float value) { blue=value; return value*tint.blue; }
    public float alpha(float value) { alpha=value; return value; }
    /** Sink must pass through color(), as GlStateManager's hooked method does. */
    public void reapply(Sink sink) { sink.color(red,green,blue,alpha); }
    public Scope push(Object owner,Tint tint,Sink sink) { return new Scope(owner,tint,sink); }
    public final class Scope implements AutoCloseable {
        private final Scope previous=top;
        private final Object oldOwner=TintScopeState.this.owner;
        private final Tint oldTint=TintScopeState.this.tint;
        private final float r=red,g=green,b=blue,a=alpha;
        private final Sink sink;
        private boolean closed;
        private Scope(Object owner,Tint tint,Sink sink) {
            this.sink=java.util.Objects.requireNonNull(sink);
            TintScopeState.this.tint=java.util.Objects.requireNonNull(tint);
            TintScopeState.this.owner=owner; top=this;
        }
        public void close() {
            if(closed) return;
            if(top!=this) throw new IllegalStateException("Out-of-order tint scope");
            closed=true; top=previous; owner=oldOwner; tint=oldTint;
            red=r; green=g; blue=b; alpha=a;
            reapply(sink);
        }
    }
}

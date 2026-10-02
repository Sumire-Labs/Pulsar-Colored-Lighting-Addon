package com.sumirelabs.pcla.network;

import com.sumirelabs.pcla.client.TintScopeState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TintScopeStateTest {
    private static final class Harness {
        final TintScopeState state=new TintScopeState();
        final float[] output=new float[4];
        final TintScopeState.Sink sink=(r,g,b,a)->state.color(r,g,b,a,(rr,gg,bb,aa)-> { output[0]=rr; output[1]=gg; output[2]=bb; output[3]=aa; });
    }
    @Test void nestedAndNeutralScopesRestoreLogicalColorWithoutMultiplyingTintTwice() {
        var h=new Harness(); var red=new TintScopeState.Tint(1,0.2f,0.1f);
        Object outer=new Object(),inner=new Object();
        h.sink.color(0.5f,0.6f,0.7f,0.8f);
        try(var ignored=h.state.push(outer,red,h.sink)) {
            h.state.reapply(h.sink); assertArrayEquals(new float[]{0.5f,0.12f,0.07f,0.8f},h.output,0.00001f);
            try(var neutral=h.state.push(inner,TintScopeState.Tint.WHITE,h.sink)) {
                h.state.reapply(h.sink); assertArrayEquals(new float[]{0.5f,0.6f,0.7f,0.8f},h.output,0.00001f);
                h.sink.color(1,1,1,1); assertSame(inner,h.state.owner());
            }
            assertSame(outer,h.state.owner()); assertArrayEquals(new float[]{0.5f,0.12f,0.07f,0.8f},h.output,0.00001f);
        }
        assertEquals(0,h.state.depth()); assertNull(h.state.owner());
        assertArrayEquals(new float[]{0.5f,0.6f,0.7f,0.8f},h.output,0.00001f);
    }
    @Test void exceptionsAndRepeatedWhiteColorCallsCannotLeakTheEntityTint() {
        var h=new Harness();
        assertThrows(IllegalStateException.class,()-> {
            try(var scope=h.state.push("entity",new TintScopeState.Tint(0.1f,0.2f,1),h.sink)) {
                for(int i=0;i<10;i++) h.sink.color(1,1,1,0.5f);
                assertArrayEquals(new float[]{0.1f,0.2f,1,0.5f},h.output);
                throw new IllegalStateException("renderer failed");
            }
        });
        assertEquals(0,h.state.depth()); assertArrayEquals(new float[]{1,1,1,1},h.output);
        h.sink.color(0.2f,0.3f,0.4f,0.5f); assertArrayEquals(new float[]{0.2f,0.3f,0.4f,0.5f},h.output);
    }
    @Test void closeOrderAndInvalidFactorsAreRejectedWithoutCorruptingTheStack() {
        var h=new Harness(); var a=h.state.push("a",TintScopeState.Tint.WHITE,h.sink); var b=h.state.push("b",TintScopeState.Tint.WHITE,h.sink);
        assertThrows(IllegalStateException.class,a::close); assertEquals(2,h.state.depth());
        b.close(); a.close(); a.close(); assertEquals(0,h.state.depth());
        assertThrows(IllegalArgumentException.class,()->new TintScopeState.Tint(Float.NaN,1,1));
        assertThrows(IllegalArgumentException.class,()->new TintScopeState.Tint(-1,1,1));
    }
}

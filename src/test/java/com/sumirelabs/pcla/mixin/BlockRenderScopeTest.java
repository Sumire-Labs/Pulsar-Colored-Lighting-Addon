package com.sumirelabs.pcla.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sumirelabs.pcla.client.BlockTint;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.state.IBlockState;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockRenderScopeTest {
    private static final IBlockState STATE=(IBlockState)Proxy.newProxyInstance(
            IBlockState.class.getClassLoader(),new Class<?>[]{IBlockState.class},(proxy,method,args)->{
                if(method.getName().equals("isFullCube"))return false;
                throw new AssertionError("Unexpected world access in scope-only test: "+method);
            });
    private static boolean call(boolean forge, Operation<Boolean> body) {
        Class<?> type=forge ? MixinForgeBlockTint.class : MixinBlockTint.class;
        Method wrapper=Arrays.stream(type.getDeclaredMethods()).filter(m->m.getName().equals("pcla$render")).findFirst().orElseThrow();
        wrapper.setAccessible(true);
        Object[] arguments=forge ? new Object[]{null,null,null,STATE,BlockPos.ORIGIN,null,true,17L,body}
                : new Object[]{null,null,STATE,BlockPos.ORIGIN,null,true,17L,body};
        try { return (Boolean)wrapper.invoke(forge ? null : new MixinBlockTint(){},arguments); }
        catch(InvocationTargetException e) {
            if(e.getCause() instanceof RuntimeException failure) throw failure;
            throw new AssertionError(e.getCause());
        } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @Test void bothActualWrappersRestoreTheirScopeAndForwardArgumentsOnSuccessOrFailure() {
        boolean enabled=BlockTint.enabled; BlockTint.enabled=false; // No world/GL is needed to exercise scope lifetime.
        try {
            for(boolean forge:new boolean[]{false,true}) {
                assertEquals(0,BlockTint.scopeDepth());
                assertTrue(call(forge,args->{
                    assertEquals(1,BlockTint.scopeDepth());
                    assertEquals(forge ? 8 : 7,args.length);
                    assertSame(BlockPos.ORIGIN,args[forge ? 4 : 3]);
                    assertEquals(17L,args[args.length-1]); return true;
                }));
                assertEquals(0,BlockTint.scopeDepth());
                var failure=new IllegalStateException("broken mod model");
                assertSame(failure,assertThrows(IllegalStateException.class,()->call(forge,args->{throw failure;})));
                assertEquals(0,BlockTint.scopeDepth());
            }
        } finally { while(BlockTint.scopeDepth()>0) BlockTint.end(); BlockTint.enabled=enabled; }
    }

    @Test void caughtNestedForgeFailureRetainsTheOuterVanillaScope() {
        boolean enabled=BlockTint.enabled; BlockTint.enabled=false;
        try {
            assertFalse(call(false,args->{
                var failure=new IllegalStateException("nested model");
                assertSame(failure,assertThrows(IllegalStateException.class,()->call(true,inner->{
                    assertEquals(2,BlockTint.scopeDepth()); throw failure;
                })));
                assertEquals(1,BlockTint.scopeDepth()); return false;
            }));
            assertEquals(0,BlockTint.scopeDepth());
        } finally { while(BlockTint.scopeDepth()>0) BlockTint.end(); BlockTint.enabled=enabled; }
    }
}

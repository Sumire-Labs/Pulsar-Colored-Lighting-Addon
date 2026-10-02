package com.sumirelabs.pcla.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeOptionsTest {
    @Test void automaticRendererAdapterHonorsExplicitStartupOverrides() {
        assertTrue(RuntimeOptions.rendererOverride(null,true));assertFalse(RuntimeOptions.rendererOverride(null,false));
        assertFalse(RuntimeOptions.rendererOverride("false",true));assertTrue(RuntimeOptions.rendererOverride("true",false));
    }
}

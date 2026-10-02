package com.sumirelabs.pcla.client;

/** Optional sky contribution within the existing experimental block/entity render hooks. */
public final class SkyTint {
    public static final boolean INSTALLED=com.sumirelabs.pcla.config.RuntimeOptions.skyTint();
    public static volatile boolean enabled=INSTALLED;
    private SkyTint() {}
}

package com.sumirelabs.pcla.light.world;

/** A bounded calculation owner, independent of its transport/subscription key. */
public interface LightDomain<C,R> {
    CapturedRgbRegion.Bounds bounds();
    RegionPublication<C,R> gate();
}

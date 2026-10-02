package com.sumirelabs.pcla.config;

import com.sumirelabs.pcla.light.Rgb;
import java.util.Objects;

public record TargetRule(Selector target, RuleMode mode, Rgb rgb) {
    public TargetRule { Objects.requireNonNull(target); Objects.requireNonNull(mode); }
    public String canonical() { return target.canonical() + ":" + mode + ":" + (rgb == null ? "inherit" : rgb.packed()); }
}

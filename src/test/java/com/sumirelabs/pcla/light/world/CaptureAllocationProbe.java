package com.sumirelabs.pcla.light.world;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.BitSet;

/** Opt-in detached allocation probe, not a Minecraft latency or FPS benchmark. */
public final class CaptureAllocationProbe {
    private static volatile CapturedRgbRegion.Capture sink;

    public static void main(String[] args) {
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        System.out.println("case,cells,sampled,median_bytes_per_refresh,median_ns_per_refresh");
        for (int sections : new int[]{1, 2}) {
            var bounds = new CapturedRgbRegion.Bounds(0, 0, 0, sections, sections, sections);
            for (String scenario : new String[]{"empty", "low", "center", "high", "contextual"}) {
                var view = new CapturedRgbRegion(bounds);
                var dirty = new BitSet();
                int changed = switch (scenario) {
                    case "low" -> 0;
                    case "high" -> bounds.cells() - 1;
                    default -> bounds.inputCell(bounds.sizeX() / 2, bounds.sizeY() / 2, bounds.sizeZ() / 2);
                };
                if (!scenario.equals("empty") && !scenario.equals("contextual")) dirty.set(changed);
                int[] emission = {0};
                CapturedRgbRegion.Reader reader = (cell, input) -> {
                    input.source = cell == changed ? emission[0] : 0;
                    input.blockedFaces = 0;
                    input.contextual = scenario.equals("contextual") && cell == changed;
                    Arrays.fill(input.incoming, 0x111);
                };
                view.refresh(reader, new BitSet(), true);
                for (int i = 0; i < 5000; i++) {
                    emission[0] = (i & 1) == 0 ? 0xf00 : 0;
                    sink = view.refresh(reader, dirty, false);
                }
                double[] allocations = new double[5], times = new double[5];
                long thread = Thread.currentThread().threadId();
                for (int batch = 0; batch < 5; batch++) {
                    long bytes = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
                    for (int i = 0; i < 2000; i++) {
                        emission[0] = (i & 1) == 0 ? 0xf00 : 0;
                        sink = view.refresh(reader, dirty, false);
                    }
                    times[batch] = (System.nanoTime() - start) / 2000.0;
                    allocations[batch] = (bean.getThreadAllocatedBytes(thread) - bytes) / 2000.0;
                }
                Arrays.sort(allocations); Arrays.sort(times);
                System.out.printf(java.util.Locale.ROOT, "%s,%d,%d,%.1f,%.1f%n",
                        scenario, bounds.cells(), sink.sampledCells(), allocations[2], times[2]);
            }
        }
    }
}

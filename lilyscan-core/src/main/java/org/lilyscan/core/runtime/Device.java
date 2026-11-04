package org.lilyscan.core.runtime;

import java.util.Locale;
import java.util.Map;

/**
 * The compute device for the learned stages (port of {@code lilyscan/runtime/device.py}).
 * The Java build has no CUDA backend yet (the learned stages arrive in JM5+), so the
 * device is always the CPU, with the reason Python gives when no CUDA device is found.
 */
public final class Device {
    private Device() {}

    /** The report's {@code device} string, e.g. {@code cpu (no CUDA device detected)}. */
    public static String describe() {
        return describe(System.getenv());
    }

    static String describe(Map<String, String> env) {
        String requested = env.getOrDefault("LILYSCAN_DEVICE", "auto").strip().toLowerCase(Locale.ROOT);
        if (requested.isEmpty()) requested = "auto";
        return switch (requested) {
            case "cpu" -> "cpu (LILYSCAN_DEVICE=cpu)";
            case "cuda" -> "cpu (LILYSCAN_DEVICE=cuda but CUDA unavailable)";
            case "auto" -> "cpu (no CUDA device detected)";
            default -> throw new IllegalArgumentException(
                    "LILYSCAN_DEVICE must be auto, cpu, or cuda; got '" + requested + "'");
        };
    }
}

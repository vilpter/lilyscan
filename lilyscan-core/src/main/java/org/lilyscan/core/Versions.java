package org.lilyscan.core;

/**
 * Pinned external tool versions and the application version.
 *
 * <p>Mirror of the Python {@code lilyscan/runtime/config.py} pins (D5, D7). These are the
 * versions Lilyscan is tested against; changing one is a deliberate change of its own, with a
 * full eval run attached.
 */
public final class Versions {

    /** Pinned Audiveris release, linked in-process via the composite build (D15). */
    public static final String AUDIVERIS = "5.11.0";

    /** Pinned LilyPond release, invoked as a subprocess (D5). */
    public static final String LILYPOND = "2.26.0";

    /** This application's version. */
    public static final String APP = "0.1.0-SNAPSHOT";

    private Versions() {}
}

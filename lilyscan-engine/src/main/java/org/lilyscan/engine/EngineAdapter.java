package org.lilyscan.engine;

import org.audiveris.omr.Main;

/**
 * Drives Audiveris in-process (D15) and imports its {@code .omr} model to the IR (Stage 3).
 *
 * <p>JM1: proves Audiveris's classes are on the classpath (composite build). The forked-worker
 * launcher (D16), transcription through the Audiveris book API, and the {@code .omr} reader that
 * walks Audiveris's interpretation graph ({@code SIGraph}, {@code Inter}, {@code Relation}) as
 * live objects are built on top of this.
 */
public final class EngineAdapter {

    public EngineAdapter() {}

    /**
     * Whether the in-process Audiveris classes are available on the classpath. References a real
     * Audiveris class so this fails to compile if the composite build is not wired.
     */
    public boolean engineAvailable() {
        return Main.class.getName().startsWith("org.audiveris");
    }
}

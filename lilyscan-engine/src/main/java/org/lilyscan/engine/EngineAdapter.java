package org.lilyscan.engine;

/**
 * Drives Audiveris in-process (D15) and imports its {@code .omr} model to the IR (Stage 3).
 *
 * <p>Skeleton for JM0. JM1 adds: the forked-worker launcher (D16), transcription through the
 * Audiveris book API, and the {@code .omr} reader that walks Audiveris's interpretation graph
 * ({@code SIGraph}, {@code Inter}, {@code Relation}) as live objects.
 */
public final class EngineAdapter {

    public EngineAdapter() {}

    /** Whether the in-process Audiveris classes are available on the classpath (wired in JM1). */
    public boolean engineAvailable() {
        return false;
    }
}

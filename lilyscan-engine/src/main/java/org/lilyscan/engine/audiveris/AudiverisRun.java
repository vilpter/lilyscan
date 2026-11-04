package org.lilyscan.engine.audiveris;

import java.nio.file.Path;
import java.util.List;

/**
 * One Audiveris batch run: what was run, how it ended, and what it wrote. Port of
 * {@code AudiverisRun} in {@code lilyscan/engine/audiveris/runner.py}.
 */
public record AudiverisRun(List<String> command, int returncode, double wallS, String log,
        List<Path> omrFiles, List<Path> mxlFiles, boolean timedOut) {

    /** The run finished normally and exported MusicXML. */
    public boolean ok() {
        return returncode == 0 && !timedOut && !mxlFiles.isEmpty();
    }

    public List<String> ocrProblems() {
        return EngineLog.ocrProblems(log);
    }

    public List<String> stepErrors() {
        return EngineLog.stepErrors(log);
    }

    public List<Integer> invalidSheets() {
        return EngineLog.invalidSheets(log);
    }

    public List<Integer> failedSheets() {
        return EngineLog.failedSheets(log);
    }
}

package org.lilyscan.engine.audiveris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.lilyscan.core.runtime.Config;
import org.lilyscan.core.runtime.Settings;

/** Port of tests/test_audiveris_runner.py, plus the process handling the Python tests mocked. */
class AudiverisRunnerTest {

    static Settings external(String bin, Map<String, String> more) {
        Map<String, String> env = new HashMap<>(more);
        env.put("AUDIVERIS_BIN", bin);
        return Settings.fromEnv(env);
    }

    @Test
    void ocrProblemsDetectsMissingAndFastModels() {
        String log = "INFO  StepMonitoring | TEXTS\n"
                + "WARN [x] TesseractOCR.java:335 | The collection of supported languages is empty\n"
                + "Error: Tesseract (legacy) engine requested, but components are not present in /t!!\n"
                + "WARN [x] TesseractOCR 341  | Language 'ita' is not supported\n"
                + "WARN [x] OcrUtil 106  | Missing support for 'eng+ita' language(s)\n"
                + "INFO  StepMonitoring | MEASURES\n";
        List<String> found = EngineLog.ocrProblems(log);
        assertEquals(3, found.size());
        assertTrue(found.get(2).contains("Missing support for 'eng+ita'"));
        assertEquals(List.of(), EngineLog.ocrProblems("INFO  StepMonitoring | TEXTS\n"));
    }

    @Test
    void stepErrorsAreDistinctMessages() {
        String log = "WARN PeakGraph 305  | No system found\n"
                + "Book 2044 | Error processing stub org.audiveris.omr.step.StepException: No system found\n"
                + "Caused by: org.audiveris.omr.step.StepException: No system found\n";
        assertEquals(List.of("No system found"), EngineLog.stepErrors(log));
    }

    @Test
    void stepCrashesAreReported() {
        String log = "WARN  [p] Book 2044 | Error processing stub java.lang.NullPointerException: ...\n"
                + "WARN  [p] CLI 956  | Exception occurred java.lang.Exception: Error in reaching step PAGE\n"
                + "java.lang.Exception: Error in reaching step PAGE\n";
        assertEquals(List.of("Error in reaching step PAGE"), EngineLog.stepErrors(log));
    }

    @Test
    void aCrashAfterTheStepsIsReported() {
        String log = "WARN  [p] CLI 956  | Exception occurred java.lang.NullPointerException: Cannot invoke"
                + " \"org.audiveris.omr.sheet.Sheet.getPages()\" because \"sheet\" is null\n"
                + "java.lang.NullPointerException: Cannot invoke ...\n"
                + "\tat org.audiveris.omr.score.Score.getPage(Score.java:485)\n";
        assertEquals(List.of("NullPointerException: Cannot invoke \"org.audiveris.omr.sheet.Sheet.getPages()\""
                + " because \"sheet\" is null"), EngineLog.stepErrors(log));
    }

    @Test
    void failedSheetsCombineInvalidAndCrashed() {
        String log = "INFO  [pages#2] Sheet pages#2 flagged as invalid\n"
                + "WARN  [pages#3] Book 2044 | Error processing stub java.lang.NullPointerException\n"
                + "WARN  [pages#5] Book.java:2044 | Error processing stub x\n";
        assertEquals(List.of(2), EngineLog.invalidSheets(log));
        assertEquals(List.of(2, 3, 5), EngineLog.failedSheets(log));
    }

    @Test
    void passesConfiguredOcrLanguages(@TempDir Path tmp) {
        Settings s = external("audiveris", Map.of("LILYSCAN_OCR_LANGUAGES", "eng+lat+deu+fra"));
        List<String> cmd = AudiverisRunner.command(List.of(tmp.resolve("a.pdf")), tmp.resolve("out"), null, null, s, null);
        assertTrue(cmd.contains(Config.AUDIVERIS_OCR_LANGUAGES_KEY + "=eng+lat+deu+fra"));
        cmd = AudiverisRunner.command(List.of(tmp.resolve("a.pdf")), tmp.resolve("out"), null, null, s, "ENG+ita");
        assertTrue(cmd.contains(Config.AUDIVERIS_OCR_LANGUAGES_KEY + "=eng+ita"));
    }

    @Test
    void passesTheStepTimeout(@TempDir Path tmp) {
        Settings s = external("audiveris", Map.of("AUDIVERIS_STEP_TIMEOUT_S", "600"));
        assertTrue(AudiverisRunner.command(List.of(tmp.resolve("a.pdf")), tmp.resolve("out"), null, null, s, null)
                .contains(Config.AUDIVERIS_STEP_TIMEOUT_KEY + "=600"));
        // A caller may still set it explicitly.
        assertTrue(AudiverisRunner.command(List.of(tmp.resolve("a.pdf")), tmp.resolve("out"),
                Map.of(Config.AUDIVERIS_STEP_TIMEOUT_KEY, "30"), null, s, null)
                .contains(Config.AUDIVERIS_STEP_TIMEOUT_KEY + "=30"));
    }

    @Test
    void rejectsOcrLanguagesAsRawConstant(@TempDir Path tmp) {
        assertThrows(IllegalArgumentException.class, () -> AudiverisRunner.command(List.of(tmp.resolve("a.pdf")),
                tmp.resolve("out"), Map.of(Config.AUDIVERIS_OCR_LANGUAGES_KEY, "eng"), null,
                Settings.fromEnv(Map.of()), null));
    }

    @Test
    void buildCommandOrdersOptionsBeforeInputs() {
        List<String> cmd = AudiverisRunner.buildCommand(List.of("audiveris"), List.of(Path.of("a.pdf"), Path.of("b.png")),
                Path.of("out"), Map.of("z.Key", "1", "a.Key", "2"), "1 3-4");
        assertEquals(List.of("audiveris", "-batch", "-transcribe", "-export", "-save", "-output", "out"), cmd.subList(0, 7));
        assertEquals(List.of("-constant", "a.Key=2", "-constant", "z.Key=1"), cmd.subList(7, 11));
        assertEquals(List.of("-sheets", "1 3-4"), cmd.subList(11, 13));
        assertEquals(List.of("--", "a.pdf", "b.png"), cmd.subList(13, cmd.size()));
    }

    @Test
    void buildCommandRejectsInjectionInKeys() {
        assertThrows(IllegalArgumentException.class, () -> AudiverisRunner.buildCommand(List.of("audiveris"),
                List.of(Path.of("a.pdf")), Path.of("o"), Map.of("-print x", "1"), null));
    }

    @Test
    void buildCommandRequiresInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> AudiverisRunner.buildCommand(List.of("audiveris"), List.of(), Path.of("o"), null, null));
    }

    @Test
    void engineTimeoutGrowsWithPages() {
        Settings s = Settings.fromEnv(Map.of("AUDIVERIS_TIMEOUT_S", "900", "AUDIVERIS_TIMEOUT_PER_PAGE_S", "240"));
        assertEquals(900, AudiverisRunner.engineTimeout(s, null));
        assertEquals(900, AudiverisRunner.engineTimeout(s, 2)); // short books get the minimum
        assertEquals(4800, AudiverisRunner.engineTimeout(s, 20));
    }

    @Test
    void bundledEngineRunsInAForkedWorker() {
        List<String> launcher = AudiverisRunner.launcher(Settings.fromEnv(Map.of("AUDIVERIS_MAX_HEAP", "2G")));
        assertTrue(launcher.get(0).endsWith("java"));
        assertTrue(launcher.contains("-Xmx2G"));
        assertTrue(launcher.contains("-Dsun.java2d.uiScale=1"));
        assertEquals(List.of(EngineWorker.class.getName(), "audiveris"), launcher.subList(launcher.size() - 2, launcher.size()));
    }

    /** A stand-in Audiveris launcher: writes outputs into -output, prints, exits with $CODE. */
    static Path fakeLauncher(Path dir, String body) throws Exception {
        Path f = dir.resolve("fake-audiveris.sh");
        Files.writeString(f, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"));
        return f;
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void runCapturesTheLogAndFindsOutputs(@TempDir Path tmp) throws Exception {
        Path bin = fakeLauncher(tmp, """
                while [ "$1" != "-output" ]; do shift; done; out="$2"
                mkdir -p "$out/sub"; : > "$out/book.omr"; : > "$out/sub/b.mxl"; : > "$out/a.mxl"
                echo "INFO out ünïcode"; echo "WARN x | Missing support for 'eng+zzz'" >&2
                exit 0
                """);
        AudiverisRun run = AudiverisRunner.run(List.of(tmp.resolve("p.png")), tmp.resolve("engine"), null, null,
                external(bin.toString(), Map.of()), null, 1);
        assertTrue(run.ok());
        assertEquals(0, run.returncode());
        assertFalse(run.timedOut());
        assertEquals("INFO out ünïcode\nWARN x | Missing support for 'eng+zzz'\n", run.log());
        assertEquals(run.log(), Files.readString(tmp.resolve("engine/audiveris.log")));
        assertEquals(List.of(tmp.resolve("engine/a.mxl"), tmp.resolve("engine/sub/b.mxl")), run.mxlFiles());
        assertEquals(List.of(tmp.resolve("engine/book.omr")), run.omrFiles());
        assertEquals(1, run.ocrProblems().size());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aRunThatOverstaysIsStopped(@TempDir Path tmp) throws Exception {
        Path bin = fakeLauncher(tmp, "echo started; echo noise >&2; sleep 30\n");
        Settings s = external(bin.toString(), Map.of("AUDIVERIS_TIMEOUT_S", "1", "AUDIVERIS_TIMEOUT_PER_PAGE_S", "0"));
        AudiverisRun run = AudiverisRunner.run(List.of(tmp.resolve("p.png")), tmp.resolve("engine"), null, null, s, null, null);
        assertTrue(run.timedOut());
        assertEquals(-1, run.returncode());
        assertFalse(run.ok());
        assertEquals("started\n", run.log()); // like Python: only stdout survives a timeout
        assertTrue(run.wallS() < 20);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFailedRunIsNotOk(@TempDir Path tmp) throws Exception {
        Path bin = fakeLauncher(tmp, "echo 'org.audiveris.omr.step.StepException: No system found'; exit 1\n");
        AudiverisRun run = AudiverisRunner.run(List.of(tmp.resolve("p.png")), tmp.resolve("engine"), null, null,
                external(bin.toString(), Map.of()), null, null);
        assertFalse(run.ok());
        assertEquals(1, run.returncode());
        assertEquals(List.of("No system found"), run.stepErrors());
    }
}

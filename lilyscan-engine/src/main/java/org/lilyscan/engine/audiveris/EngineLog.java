package org.lilyscan.engine.audiveris;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an Audiveris run's log says went wrong. Port of the log analysis in
 * {@code lilyscan/engine/audiveris/runner.py}; the worker runs Audiveris's own batch CLI, so its
 * log is the one the Python runner read.
 */
public final class EngineLog {
    private EngineLog() {}

    /** Audiveris keeps going when Tesseract is unusable and simply skips text recognition, which
     *  silently drops lyrics, titles, and chord names. */
    private static final List<String> OCR_PROBLEMS = List.of(
            // No traineddata files found.
            "The collection of supported languages is empty",
            // LSTM-only ("fast") models; Audiveris needs the legacy engine components.
            "Tesseract (legacy) engine requested, but components are not present",
            // A requested language has no traineddata installed.
            "Missing support for");

    /** A step that gave up ("StepException: No system found") or crashed ("Error in reaching step
     *  PAGE", after an internal exception), or a crash after the steps, when the book's scores are
     *  assembled ("Exception occurred java.lang.NullPointerException: ..."). */
    private static final Pattern STEP_ERROR = Pattern.compile(
            "StepException: (.+)$|java\\.lang\\.Exception: (Error in reaching step \\w+)"
                    + "|Exception occurred java\\.lang\\.((?!Exception:)\\w+: .+)$",
            Pattern.UNICODE_CHARACTER_CLASS);

    /** A sheet Audiveris gave up on ("Sheet pages#6 flagged as invalid"); one such sheet makes the
     *  whole book fail to export. */
    private static final Pattern INVALID_SHEET =
            Pattern.compile("Sheet \\S*#(\\d+) flagged as invalid", Pattern.UNICODE_CHARACTER_CLASS);

    /** A sheet whose processing threw: "WARN [pages#3] Book 2044 | Error processing stub ..." on the
     *  console ("Book.java:2044" in the log file). That too fails the whole book's export. */
    private static final Pattern CRASHED_SHEET = Pattern.compile(
            "\\[\\S*#(\\d+)\\]\\s+\\S+(?:\\.java:|\\s+)\\d+\\s+\\|\\s+Error processing stub",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static List<String> lines(String log) {
        List<String> out = new ArrayList<>(List.of(log.split("\\R", -1)));
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1); // str.splitlines drops the empty piece after a final break
        }
        return out;
    }

    private static List<Integer> numbers(Pattern p, String log, TreeSet<Integer> into) {
        Matcher m = p.matcher(log);
        while (m.find()) {
            into.add(Integer.parseInt(m.group(1)));
        }
        return new ArrayList<>(into);
    }

    /** Numbers (from 1) of the sheets Audiveris flagged as invalid. */
    public static List<Integer> invalidSheets(String log) {
        return numbers(INVALID_SHEET, log, new TreeSet<>());
    }

    /** Numbers (from 1) of the sheets Audiveris flagged as invalid or failed on. */
    public static List<Integer> failedSheets(String log) {
        TreeSet<Integer> all = new TreeSet<>(invalidSheets(log));
        return numbers(CRASHED_SHEET, log, all);
    }

    /** Log lines showing that Audiveris ran without working OCR. */
    public static List<String> ocrProblems(String log) {
        List<String> out = new ArrayList<>();
        for (String line : lines(log)) {
            for (String p : OCR_PROBLEMS) {
                if (line.contains(p)) {
                    out.add(line.strip());
                    break;
                }
            }
        }
        return out;
    }

    /** Distinct Audiveris step failure messages, e.g. {@code No system found}. */
    public static List<String> stepErrors(String log) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (String line : lines(log)) {
            Matcher m = STEP_ERROR.matcher(line);
            if (m.find()) {
                String g = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
                found.add(g.strip());
            }
        }
        return new ArrayList<>(found);
    }
}

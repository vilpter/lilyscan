package org.lilyscan.core.lilypond;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A generated LilyPond project: files keyed by relative path, plus the measure map that turns
 * bar-check reports back into measures. Port of {@code LyProject} in generate.py.
 *
 * @param files relative path -> text, sorted by path
 * @param measureLines (relative file, 1-based line) -> the measure it holds
 * @param staffVars music variable name -> its staff; lets the measure map be rebuilt from edited
 *     files ({@link Generate#scanMeasureLines})
 */
public record LyProject(Map<String, String> files, Map<Line, MeasureRef> measureLines,
        Map<String, StaffRef> staffVars) {

    /** A line of a project file. */
    public record Line(String file, int line) {}

    /** A measure of one staff: part id, staff number, measure number as printed. */
    public record MeasureRef(String part, int staff, String measure) {}

    /** A staff: part id and staff number. */
    public record StaffRef(String part, int staff) {}

    /** Writes the files under {@code root} (UTF-8, LF). */
    public void write(Path root) {
        try {
            for (Map.Entry<String, String> e : files.entrySet()) {
                Path p = root.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

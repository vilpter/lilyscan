package org.lilyscan.eval.synth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.NoteHead;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.lilypond.Compile;
import org.lilyscan.core.qa.Checks;
import org.lilyscan.core.runtime.Settings;

/**
 * The admission checks of a corpus item: is the ground truth well formed, and does the engraving
 * show exactly the ground truth? They look only at the ground truth and its engraving, never at
 * engine output, so they cannot favour pieces the engine reads well. An item with any finding is
 * rejected: it stays in the manifest, marked so, and is never scored.
 *
 * <ul>
 *   <li>{@code overlap}: within one voice, an event starts before the previous one ends.
 *   <li>{@code overfull}: a voice runs past the end of its measure.
 *   <li>{@code measure-count}: the staves do not all have the same number of measures.
 *   <li>{@code compile}: engraving the ground truth gives an error, a LilyPond internal warning or a
 *       failing bar check.
 *   <li>{@code engraving}: the notes and rests LilyPond engraved (read back with an event listener)
 *       differ from the ground truth's, staff by staff.
 *   <li>{@code page-fit}: the engraving runs off the page.
 * </ul>
 */
public final class Admission {
    private Admission() {}

    public static final String PASS = "pass";
    public static final String REJECTED = "rejected";
    /** At most this many differences are listed per staff. */
    static final int MAX_DIFFS = 8;

    /** The outcome: {@link #PASS} when there are no findings. */
    public record Result(String status, List<Map<String, Object>> findings) {
        public boolean passed() {
            return findings.isEmpty();
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status);
            m.put("findings", findings);
            return m;
        }
    }

    /** All checks: {@code ly} is the engraved LilyPond source, {@code pdf} its engraving. */
    public static Result check(Score groundTruth, Path ly, Path pdf, Settings s) {
        List<Map<String, Object>> findings = new ArrayList<>(groundTruthFindings(groundTruth));
        findings.addAll(engravingFindings(groundTruth, ly, s));
        Checks.CheckResult fit = Checks.pageFitCheck(pdf);
        if (!fit.passed()) {
            for (Map<String, Object> d : fit.details()) findings.add(finding("page-fit", null, null, String.valueOf(d.get("message"))));
        }
        return new Result(findings.isEmpty() ? PASS : REJECTED, findings);
    }

    // ---- the ground truth ----

    static List<Map<String, Object>> groundTruthFindings(Score score) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Score.PartStaff ps : score.staves()) {
            String staff = staffLabel(ps);
            counts.put(staff, ps.staff().measures.size());
            Frac length = null;
            for (Measure m : ps.staff().measures) {
                if (m.time != null) length = m.time.measureLength();
                for (Voice v : m.voices) {
                    List<Event> events = v.events.stream().filter(e -> !e.grace)
                            .sorted(Comparator.comparing((Event e) -> e.offset)).toList();
                    for (int i = 1; i < events.size(); i++) {
                        Event prev = events.get(i - 1), e = events.get(i);
                        if (e.offset.lt(prev.offset.add(prev.duration))) {
                            out.add(finding("overlap", staff, m.number, "voice " + v.number + ": " + describe(e)
                                    + " starts before " + describe(prev) + " ends"));
                        }
                    }
                    if (length != null && v.end().gt(length) && !m.implicit) {
                        out.add(finding("overfull", staff, m.number, "voice " + v.number + " ends at " + v.end()
                                + ", the measure is " + length));
                    }
                }
            }
        }
        if (counts.values().stream().distinct().count() > 1) {
            out.add(finding("measure-count", null, null, "measures per staff: " + counts));
        }
        return out;
    }

    private static String describe(Event e) {
        return e.kind + " at " + e.offset + " (" + e.duration + ")";
    }

    // ---- the engraving ----

    /**
     * A LilyPond engraver on every staff that writes each note, rest and multi-measure rest it
     * hears (staff number in creation order, bar number, position in the bar, duration, MIDI
     * pitch) to {@code out}. Grace notes are left out, as in the comparison.
     */
    static String listener(Path out) {
        String file = out.toAbsolutePath().toString().replace("\\", "/").replace("\"", "\\\"");
        return """
                #(define lilyscan-admission-staves 0)
                #(define lilyscan-admission-out (open-output-file "%s"))
                #(define (lilyscan-admission-listener context)
                   (set! lilyscan-admission-staves (1+ lilyscan-admission-staves))
                   (let ((staff lilyscan-admission-staves))
                     (define (emit kind pitch event)
                       (let ((now (ly:context-current-moment context)))
                         (if (zero? (ly:moment-grace-numerator now))
                             (format lilyscan-admission-out "~a\\t~a\\t~a\\t~a\\t~a\\t~a\\n" staff kind
                                     (ly:context-property context 'currentBarNumber)
                                     (ly:moment-main (ly:context-property context 'measurePosition))
                                     (ly:moment-main (ly:duration->moment (ly:event-property event 'duration)))
                                     pitch))))
                     (make-engraver
                      (listeners
                       ((note-event engraver event)
                        (let ((p (ly:event-property event 'pitch)))
                          (if (ly:pitch? p) (emit "note" (+ 60 (ly:pitch-semitones p)) event))))
                       ((rest-event engraver event) (emit "rest" "-" event))
                       ((multi-measure-rest-event engraver event) (emit "rest" "-" event))))))
                \\layout { \\context { \\Staff \\consists #lilyscan-admission-listener } }
                """.formatted(file);
    }

    static List<Map<String, Object>> engravingFindings(Score score, Path ly, Settings s) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path dir = ly.toAbsolutePath().getParent();
        Path wrapper = dir.resolve("admission.ly");
        Path tmp = dir.resolve("admission-out");
        Path events = dir.resolve("admission-events.tsv");
        try {
            Files.writeString(wrapper, listener(events) + "\\include \"" + ly.getFileName() + "\"\n",
                    StandardCharsets.UTF_8);
            Compile.Result r = Compile.compileLy(wrapper, tmp, List.of("pdf"), false, List.of(), s);
            for (Compile.Diagnostic d : r.diagnostics()) {
                if (d.isError() || d.isInternal() || d.isBarcheck()) {
                    out.add(finding("compile", null, null, d.severity() + ": " + d.message()
                            + (d.line() != null ? " (" + Path.of(d.file()).getFileName() + ":" + d.line() + ")" : "")));
                }
            }
            if (!r.ok()) return out;
            out.addAll(compare(score, Files.readAllLines(events, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(wrapper);
                Files.deleteIfExists(events);
                deleteTree(tmp);
            } catch (IOException ignored) {
                // leftovers of a check do no harm
            }
        }
        return out;
    }

    /** One heard or written note or rest: measure index, position, duration, MIDI pitch (-1 for a rest). */
    record Sound(int measure, Frac position, Frac duration, int pitch) {
        String label(List<Measure> measures) {
            String m = measure >= 0 && measure < measures.size() ? measures.get(measure).number : String.valueOf(measure + 1);
            return (pitch < 0 ? "rest" : "note " + pitch) + " in m. " + m + " at " + position + " (" + duration + ")";
        }
    }

    /**
     * Compares the listener's lines with the ground truth, staff by staff. LilyPond counts in whole
     * notes, the IR in quarter notes.
     */
    static List<Map<String, Object>> compare(Score score, List<String> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Score.PartStaff> staves = score.staves();
        Map<Integer, List<Sound>> heard = new TreeMap<>();
        for (String line : lines) {
            String[] f = line.split("\t");
            if (f.length < 6) continue;
            int pitch = f[1].equals("note") ? Integer.parseInt(f[5]) : -1;
            heard.computeIfAbsent(Integer.parseInt(f[0]), k -> new ArrayList<>())
                    .add(new Sound(Integer.parseInt(f[2]) - 1, Frac.parse(f[3]).multiply(4), Frac.parse(f[4]).multiply(4), pitch));
        }
        if (heard.size() != staves.size()) {
            out.add(finding("engraving", null, null, "the engraving has " + heard.size() + " staves with music, the ground truth "
                    + staves.size()));
            return out;
        }
        int i = 0;
        for (List<Sound> engraved : heard.values()) {
            Score.PartStaff ps = staves.get(i++);
            List<Measure> measures = ps.staff().measures;
            Map<Sound, Integer> diff = new LinkedHashMap<>();
            for (Sound w : written(measures)) diff.merge(w, 1, Integer::sum);
            for (Sound h : engraved) diff.merge(h, -1, Integer::sum);
            List<String> missing = new ArrayList<>(), extra = new ArrayList<>();
            for (var e : diff.entrySet()) {
                for (int k = 0; k < Math.abs(e.getValue()); k++) {
                    (e.getValue() > 0 ? missing : extra).add(e.getKey().label(measures));
                }
            }
            if (missing.isEmpty() && extra.isEmpty()) continue;
            String staff = staffLabel(ps);
            out.add(finding("engraving", staff, null, missing.size() + " written but not engraved, " + extra.size()
                    + " engraved but not written. Not engraved: " + head(missing) + "; not written: " + head(extra)));
        }
        return out;
    }

    /** The ground truth's notes and rests, with each chord as one note per pitch. */
    static List<Sound> written(List<Measure> measures) {
        List<Sound> out = new ArrayList<>();
        for (int i = 0; i < measures.size(); i++) {
            for (Voice v : measures.get(i).voices) {
                for (Event e : v.events) {
                    if (e.grace) continue;
                    if (e.isRest()) {
                        out.add(new Sound(i, e.offset, e.duration, -1));
                    } else {
                        for (NoteHead n : e.notes) out.add(new Sound(i, e.offset, e.duration, n.pitch.midi()));
                    }
                }
            }
        }
        return out;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static String head(List<String> items) {
        if (items.isEmpty()) return "none";
        String s = String.join(", ", items.subList(0, Math.min(MAX_DIFFS, items.size())));
        return items.size() > MAX_DIFFS ? s + ", …" : s;
    }

    private static String staffLabel(Score.PartStaff ps) {
        return ps.part().id + "/" + ps.staff().number;
    }

    static Map<String, Object> finding(String check, String staff, String measure, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", check);
        if (staff != null) m.put("staff", staff);
        if (measure != null) m.put("measure", measure);
        m.put("message", message);
        return m;
    }
}

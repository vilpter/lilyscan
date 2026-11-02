package org.lilyscan.core.lilypond;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lilyscan.core.ir.Barline;
import org.lilyscan.core.ir.ChordSymbol;
import org.lilyscan.core.ir.Clef;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Lyric;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.NoteHead;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Provenance;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.Tuplet;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.lilypond.LyProject.Line;
import org.lilyscan.core.lilypond.LyProject.MeasureRef;
import org.lilyscan.core.lilypond.LyProject.StaffRef;
import org.lilyscan.core.runtime.Config;
import org.lilyscan.core.util.Py;

/**
 * Stage 7: IR -> an editable LilyPond project. Port of {@code lilyscan/lilypond/generate.py}.
 *
 * <pre>
 *     main.ly               \version, \header, includes, the full score
 *     parts/&lt;name&gt;.ly       one music variable per staff, lyrics variables
 *     chords.ly             harmonies = \chordmode { ... }   (when there are chord symbols)
 *     layout/score.ly       \score { ... } for the full score
 *     layout/part-&lt;name&gt;.ly a single-part layout reusing the same variables
 * </pre>
 *
 * <p>Pitches are absolute (D4). Every measure sits on its own line with a bar check and a
 * {@code % m. N} comment. Output is deterministic: the same IR gives the same bytes, and the
 * same bytes as the Python generator (D19).
 */
public final class Generate {
    private Generate() {}

    /** Events below this confidence get an inline {@code %{ ?? ... %}} marker. */
    public static final double LOW_CONFIDENCE = 0.5;

    private static final String VERSION = Config.LILYPOND_VERSION;
    private static final String[] VOICE_COMMANDS = {"\\voiceOne", "\\voiceTwo", "\\voiceThree", "\\voiceFour"};
    static final String[] NUMBER_WORDS = {"Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven",
        "Eight", "Nine"};
    private static final Pattern VOCAL = Pattern.compile(
            "soprano|alto|tenor|bass|voice|vocal|choir|chorus|cantus|s\\.|a\\.|t\\.|b\\.",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Repairs that change a whole staff are noted once, at the top of the staff's music,
     *  instead of on every event they touched. */
    public static final Set<String> STAFF_REPAIRS = Set.of("octave-clef", "part-merge", "lyric-verse");

    /** One staff's variable names in the generated project. */
    public static final class StaffVars {
        public final String music; // variable name, e.g. "violinIMusic"
        public final String voice; // context name of the staff's main voice, for \lyricsto
        public final List<String> lyrics = new ArrayList<>(); // one variable per verse

        StaffVars(String music, String voice) {
            this.music = music;
            this.voice = voice;
        }
    }

    /** One part's file name and variable names in the generated project. */
    public static final class PartVars {
        public final Part part;
        public final String slug; // file name stem, e.g. "violin-i"
        public final String base; // identifier stem, e.g. "violinI"
        public final List<StaffVars> staves = new ArrayList<>();

        PartVars(Part part, String slug, String base) {
            this.part = part;
            this.slug = slug;
            this.base = base;
        }
    }

    // ---- measure map ----

    private static final Pattern MUSIC_VAR = Pattern.compile("^(\\w+)\\s*=\\s*\\{", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern MEASURE_COMMENT = Pattern.compile("^\\s*% m\\. (\\S+)\\s*$",
            Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Rebuild the measure map from (possibly hand-edited) part files. Each measure's music is the
     * first non-comment line after its {@code % m. N} comment, inside the {@code <name>Music = {}
     * block of a known staff variable.
     */
    public static Map<Line, MeasureRef> scanMeasureLines(Map<String, String> files, Map<String, StaffRef> staffVars) {
        Map<Line, MeasureRef> found = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            String rel = f.getKey();
            if (!rel.startsWith("parts/")) {
                continue;
            }
            StaffRef staff = null;
            String pending = null;
            List<String> lines = splitLines(f.getValue());
            for (int n = 1; n <= lines.size(); n++) {
                String line = lines.get(n - 1);
                Matcher m = MUSIC_VAR.matcher(line);
                if (m.find()) {
                    staff = staffVars.get(m.group(1));
                    pending = null;
                    continue;
                }
                if (staff == null) {
                    continue;
                }
                if (Py.strip(line).equals("}")) {
                    staff = null;
                    continue;
                }
                Matcher c = MEASURE_COMMENT.matcher(line);
                if (c.find()) {
                    pending = c.group(1);
                    continue;
                }
                if (pending != null && !Py.strip(line).isEmpty() && !Py.strip(line).startsWith("%")) {
                    found.put(new Line(rel, n), new MeasureRef(staff.part(), staff.staff(), pending));
                    pending = null;
                }
            }
        }
        return found;
    }

    /** {@code str.splitlines()}. */
    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>(List.of(text.split("\r\n|[\n\r\u000b\f\u001c\u001d\u001e\u0085  ]", -1)));
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /** A layout line engraving one verse under a voice: \new Lyrics \lyricsto "voice" \verse */
    private static final Pattern LYRICS_LINE = Pattern.compile(
            "^[ \\t]*\\\\new Lyrics \\\\lyricsto \"([^\"]+)\" \\\\\\w+[ \\t]*(?:\\n|$)",
            Pattern.MULTILINE | Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS);

    /** The Voice context a staff's music variable is engraved in ({@code violinIMusic} is engraved
     *  as {@code violinIVoice}). */
    public static String voiceName(String music) {
        return (music.endsWith("Music") ? music.substring(0, music.length() - 5) : music) + "Voice";
    }

    /** The voices whose lyrics the layout files engrave. */
    public static Set<String> engravedLyrics(Map<String, String> files) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            if (f.getKey().startsWith("layout/")) {
                Matcher m = LYRICS_LINE.matcher(f.getValue());
                while (m.find()) {
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }

    /** A layout file without the lines that engrave these voices' lyrics. */
    public static String dropLyrics(String text, Set<String> voices) {
        Matcher m = LYRICS_LINE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, voices.contains(m.group(1)) ? "" : Matcher.quoteReplacement(m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- names ----

    private static final Pattern WORDS = Pattern.compile("[A-Za-z]+|\\d", Pattern.UNICODE_CHARACTER_CLASS);

    /** LilyPond identifiers are letters only: 'Violin II' -> 'violinII', 'P1' -> 'pOne'. */
    static String identifier(String text) {
        StringBuilder out = new StringBuilder();
        Matcher m = WORDS.matcher(text);
        while (m.find()) {
            String w = m.group();
            if (Character.isDigit(w.codePointAt(0))) {
                out.append(NUMBER_WORDS[Character.digit(w.codePointAt(0), 10)]);
            } else if (w.equals(w.toUpperCase(java.util.Locale.ROOT)) && w.length() <= 4) {
                out.append(w);
            } else {
                out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (out.isEmpty()) {
            return "part";
        }
        return Character.toLowerCase(out.charAt(0)) + out.substring(1);
    }

    static String slug(String text) {
        String s = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "part" : s;
    }

    private static String unique(String name, Set<String> used, String sep) {
        if (used.add(name)) {
            return name;
        }
        for (char letter = 'A'; letter <= 'Z'; letter++) {
            String candidate = name + sep + letter;
            if (used.add(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("too many parts named '" + name + "'");
    }

    private static String staffSuffix(int number, int count) {
        if (count == 1) {
            return "";
        }
        if (count == 2) {
            return number == 1 ? "Upper" : "Lower";
        }
        return "Staff" + NUMBER_WORDS[Math.floorMod(number, 10)];
    }

    // ---- music ----

    private static String noteToken(NoteHead head, boolean inChord) {
        return Notation.absolutePitch(head.pitch) + (head.tieStart && inChord ? "~" : "");
    }

    /** MusicXML articulation / notation names -> LilyPond post-events. */
    private static final Map<String, String> ARTICULATIONS = Map.of(
            "staccato", "-.", "staccatissimo", "-!", "accent", "->", "strong-accent", "-^", "tenuto", "--",
            "detached-legato", "-_", "spiccato", "\\staccatissimo", "fermata", "\\fermata",
            "up-bow", "\\upbow", "down-bow", "\\downbow");

    private static String eventBody(Event e) {
        String duration = Notation.writtenDuration(e.noteType, e.dots, e.duration, e.tuplet);
        StringBuilder marks = new StringBuilder();
        for (String a : e.articulations) {
            marks.append(ARTICULATIONS.getOrDefault(a, ""));
        }
        // \breathe is a command that follows the note, not a post-event.
        String breathe = e.articulations.contains("breath-mark") ? " \\breathe" : "";
        if (Event.REST.equals(e.kind) || e.notes.isEmpty()) {
            return "r" + duration + (e.articulations.contains("fermata") ? "\\fermata" : "") + breathe;
        }
        if (e.notes.size() == 1) {
            String tie = e.notes.get(0).tieStart ? "~" : "";
            return Notation.absolutePitch(e.notes.get(0).pitch) + duration + marks + tie + breathe;
        }
        List<String> heads = new ArrayList<>();
        for (NoteHead h : e.notes) {
            heads.add(noteToken(h, true));
        }
        return "<" + String.join(" ", heads) + ">" + duration + marks + breathe;
    }

    private static List<String> markers(Event e) {
        List<String> out = new ArrayList<>();
        if (e.confidence != null && e.confidence < LOW_CONFIDENCE) {
            String where = "";
            if (e.bbox != null) {
                where = " bbox=p" + (e.bbox.page + 1) + ":(" + Py.round(e.bbox.x) + "," + Py.round(e.bbox.y) + ")";
            }
            out.add("%{ ?? conf=" + String.format(java.util.Locale.ROOT, "%.2f", e.confidence) + where + " %}");
        }
        for (Provenance prov : e.provenance) {
            if ("repair".equals(prov.stage) && (prov.rule == null || !STAFF_REPAIRS.contains(prov.rule))) {
                out.add("%{ fix: " + (prov.rule == null || prov.rule.isEmpty() ? "repair" : prov.rule) + " %}");
            }
        }
        return out;
    }

    /** Python truthiness of a JSON-ish value. */
    private static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof java.util.Collection<?> c) {
            return !c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    private static int pyIntOf(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Py.parseInt(String.valueOf(v));
    }

    private static Map<StaffRef, List<String>> staffNotes(Score score) {
        Map<StaffRef, List<String>> notes = new LinkedHashMap<>();
        for (Provenance prov : score.provenance) {
            Map<String, Object> before = prov.before == null ? Map.of() : prov.before;
            if ("repair".equals(prov.stage) && before.containsKey("part") && before.containsKey("staff")) {
                StaffRef key = new StaffRef(String.valueOf(before.get("part")), pyIntOf(before.get("staff")));
                Object detail = before.get("detail");
                notes.computeIfAbsent(key, k -> new ArrayList<>())
                        .add("% fix: " + prov.rule + (truthy(detail) ? ": " + detail : ""));
            }
        }
        return notes;
    }

    /** A whole-measure rest lasting {@code length} quarters. */
    private static String fullRest(Frac length) {
        String token = Notation.singleDuration(length);
        return token != null ? "R" + token : "R1*" + length.divide(4);
    }

    private static String measureRest(Event e, Frac length) {
        if (length != null) {
            return fullRest(length);
        }
        return "R" + Notation.writtenDuration(e.noteType, e.dots, e.duration, null);
    }

    private static boolean nonZero(Frac f) {
        return f != null && !f.isZero();
    }

    /**
     * Tokens for one voice; {@code clefs} are mid-measure clef changes to interleave. A voice need
     * not start on the downbeat or be contiguous; gaps become invisible spacer rests so every
     * event keeps its onset.
     */
    private static List<String> voiceTokens(List<Event> events, Frac length, List<Clef> clefs) {
        List<String> tokens = new ArrayList<>();
        List<Clef> pending = new ArrayList<>(clefs);
        pending.sort(Comparator.comparing(c -> c.offset));
        Frac position = Frac.ZERO;
        int i = 0;
        while (i < events.size()) {
            Event e = events.get(i);
            if (e.offset.gt(position)) {
                tokens.addAll(Notation.skips(e.offset.subtract(position)));
                position = e.offset;
            }
            while (!pending.isEmpty() && pending.get(0).offset.le(e.offset)) {
                tokens.add(Notation.clefCommand(pending.remove(0)));
            }
            if (e.measureRest && events.size() == 1) {
                tokens.add(measureRest(e, length));
                position = position.add(nonZero(length) ? length : e.duration);
                i++;
                continue;
            }
            if (e.grace) {
                List<String> group = new ArrayList<>();
                while (i < events.size() && events.get(i).grace) {
                    group.add(eventBody(events.get(i)));
                    i++;
                }
                tokens.add("\\grace " + (group.size() == 1 ? group.get(0) : "{ " + String.join(" ", group) + " }"));
                continue;
            }
            if (e.tuplet != null) {
                Tuplet ratio = e.tuplet;
                List<Event> groupEvents = new ArrayList<>();
                while (i < events.size() && ratio.equals(events.get(i).tuplet) && !events.get(i).grace) {
                    groupEvents.add(events.get(i));
                    i++;
                }
                List<String> inner = new ArrayList<>();
                for (Event x : groupEvents) {
                    StringBuilder sb = new StringBuilder(eventBody(x));
                    for (String m : markers(x)) {
                        sb.append(' ').append(m);
                    }
                    inner.add(sb.toString());
                }
                String span = tupletSpan(groupEvents.get(0), ratio);
                String spanToken = span != null ? " " + span : "";
                tokens.add("\\tuplet " + ratio.actual() + "/" + ratio.normal() + spanToken + " { "
                        + String.join(" ", inner) + " }");
                for (Event x : groupEvents) {
                    position = position.add(x.duration);
                }
                continue;
            }
            tokens.add(eventBody(e));
            tokens.addAll(markers(e));
            position = position.add(e.duration);
            i++;
        }
        for (Clef c : pending) {
            tokens.add(Notation.clefCommand(c)); // a clef change right before the barline
        }
        return tokens;
    }

    /** Group length for automatic tuplet brackets: {@code normal} written notes of the first's type. */
    private static String tupletSpan(Event first, Tuplet ratio) {
        Frac written = first.duration.multiply(Frac.of(ratio.actual(), ratio.normal()));
        return Notation.singleDuration(written.multiply(ratio.normal()));
    }

    private static String measureMusic(Measure m, Frac length) {
        List<Voice> voices = new ArrayList<>();
        for (Voice v : m.voices) {
            if (!v.events.isEmpty()) {
                voices.add(v);
            }
        }
        List<Clef> midClefs = new ArrayList<>();
        for (Clef c : m.clefs) {
            if (c.offset.signum() > 0) {
                midClefs.add(c);
            }
        }
        if (voices.isEmpty()) {
            return nonZero(length) ? fullRest(length) : "s1";
        }
        if (voices.size() == 1) {
            return String.join(" ", voiceTokens(voices.get(0).events, length, midClefs));
        }
        List<String> parts = new ArrayList<>();
        for (int k = 0; k < Math.min(4, voices.size()); k++) {
            String body = String.join(" ", voiceTokens(voices.get(k).events, length, k == 0 ? midClefs : List.of()));
            String command = VOICE_COMMANDS[k];
            parts.add(k == 0 ? "{ " + command + " " + body + " }" : "\\new Voice { " + command + " " + body + " }");
        }
        return "<< " + String.join(" ", parts) + " >> \\oneVoice";
    }

    private static final Map<String, String> BAR_STYLES = Map.of(
            "light-heavy", "\\bar \"|.\"", "light-light", "\\bar \"||\"", "heavy-light", "\\bar \".|\"",
            "heavy-heavy", "\\bar \"..\"", "dashed", "\\bar \"!\"", "none", "\\bar \"\"");

    private static String barlineCommand(Barline b, boolean left) {
        if (b == null) {
            return null;
        }
        if ("forward".equals(b.repeat)) {
            return "\\bar \".|:\"";
        }
        if ("backward".equals(b.repeat)) {
            return "\\bar \":|.\"";
        }
        if (left) {
            return null;
        }
        return BAR_STYLES.get(b.style);
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern NOT_LABEL = Pattern.compile("[^\\w ,.-]", Pattern.UNICODE_CHARACTER_CLASS);

    /** An ending's numbers as printed over its bracket: "1" as "1.", "1, 2" as "1., 2.". */
    private static String voltaLabel(String number) {
        List<String> numbers = new ArrayList<>();
        Matcher m = DIGITS.matcher(number);
        while (m.find()) {
            numbers.add(m.group() + ".");
        }
        if (!numbers.isEmpty()) {
            return String.join(", ", numbers);
        }
        return Py.strip(NOT_LABEL.matcher(number).replaceAll(""));
    }

    /** Start or end volta brackets where the music is (measures are written one by one, so the
     *  brackets are set by hand rather than with \repeat volta and \alternative). */
    private static String voltaCommand(List<String> commands) {
        return "\\set Score.repeatCommands = #'(" + String.join(" ", commands) + ")";
    }

    /** Per measure index, the longest run of grace notes that opens the measure in any staff. */
    private static Map<Integer, List<String>> graceLeads(Score score) {
        Map<Integer, List<String>> leads = new HashMap<>();
        for (Score.PartStaff ps : score.staves()) {
            for (Measure m : ps.staff().measures) {
                for (Voice v : m.voices) {
                    List<String> durations = new ArrayList<>();
                    for (Event e : v.events) {
                        if (!(e.grace && e.offset.isZero())) {
                            break;
                        }
                        durations.add(Notation.writtenDuration(e.noteType, e.dots, e.duration, null));
                    }
                    if (durations.size() > leads.getOrDefault(m.index, List.of()).size()) {
                        leads.put(m.index, durations);
                    }
                }
            }
        }
        return leads;
    }

    private static String graceSkip(List<String> durations) {
        List<String> s = new ArrayList<>();
        for (String d : durations) {
            s.add("s" + d);
        }
        return "\\grace " + (s.size() == 1 ? s.get(0) : "{ " + String.join(" ", s) + " }");
    }

    private static boolean opensWithGrace(Measure m) {
        for (Voice v : m.voices) {
            if (!v.events.isEmpty() && v.events.get(0).grace && v.events.get(0).offset.isZero()) {
                return true;
            }
        }
        return false;
    }

    private static Frac longestVoice(Measure m) {
        Frac best = Frac.ZERO;
        for (Voice v : m.voices) {
            Frac d = v.duration();
            if (d.gt(best)) {
                best = d;
            }
        }
        return best;
    }

    /** Per measure index, the length every staff gives that measure: the longest voice present in
     *  any staff. Measures empty in every staff are absent. */
    private static Map<Integer, Frac> measureGrid(Score score) {
        Map<Integer, Frac> grid = new HashMap<>();
        for (Score.PartStaff ps : score.staves()) {
            for (Measure m : ps.staff().measures) {
                Frac actual = longestVoice(m);
                if (actual.gt(grid.getOrDefault(m.index, Frac.ZERO))) {
                    grid.put(m.index, actual);
                }
            }
        }
        return grid;
    }

    /** {@code Timing.measureLength} value: a rational in whole notes (LilyPond 2.26 syntax). */
    private static String measureLength(Frac length) {
        Frac whole = length.divide(4);
        return whole.denominator() == 1 ? "#" + whole.numerator() : "#" + whole.numerator() + "/" + whole.denominator();
    }

    private record StaffLines(List<String> lines, List<String> numbers) {}

    /**
     * Lines of the staff's music variable body and the measure number for each line. Where the
     * grid gives a measure a length other than its time signature's, the measure gets a
     * {@code Timing.measureLength} override (restored afterwards) and a {@code %{ ?? rhythm %}}
     * marker; staves with less music are padded with spacers.
     */
    private static StaffLines staffMusic(List<Measure> measures, Map<Integer, List<String>> leads,
            Map<Integer, Frac> lengths) {
        List<String> lines = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        Frac length = null;
        boolean overridden = false;
        Frac pickup = Frac.ZERO;
        boolean inEnding = false;
        boolean closing = false; // in a first or second ending; it ended with the last measure
        for (Measure m : measures) {
            List<String> prefix = new ArrayList<>();
            List<String> volta = new ArrayList<>();
            if (closing) {
                volta.add("(volta #f)");
                inEnding = false;
                closing = false;
            }
            Barline left = m.leftBarline;
            if (left != null && left.ending != null && !left.ending.isEmpty()
                    && (left.endingType == null || left.endingType.equals("start"))) {
                if (inEnding) {
                    volta.add("(volta #f)");
                }
                volta.add("(volta \"" + voltaLabel(left.ending) + "\")");
                inEnding = true;
            }
            if (!volta.isEmpty()) {
                prefix.add(voltaCommand(volta));
            }
            if (m.leftBarline != null) {
                String cmd = barlineCommand(m.leftBarline, true);
                if (cmd != null && !cmd.isEmpty()) {
                    prefix.add(cmd);
                }
            }
            for (Clef clef : m.clefs) {
                if (clef.offset.isZero()) {
                    prefix.add(Notation.clefCommand(clef));
                }
            }
            if (m.key != null) {
                prefix.add(Notation.keyCommand(m.key));
            }
            if (m.time != null) {
                length = m.time.measureLength();
                boolean plain = (m.time.beats == 4 && m.time.beatType == 4) || (m.time.beats == 2 && m.time.beatType == 2);
                if ("normal".equals(m.time.symbol) && plain) {
                    prefix.add("\\numericTimeSignature");
                } else if ("common".equals(m.time.symbol) || "cut".equals(m.time.symbol)) {
                    prefix.add("\\defaultTimeSignature");
                }
                prefix.add(Notation.timeCommand(m.time));
            }
            Frac own = longestVoice(m);
            boolean isPickup = false;
            if (m.implicit && m.index == 0 && length != null && own.signum() > 0 && own.lt(length)) {
                isPickup = true;
                pickup = own;
                String partial = Notation.singleDuration(own);
                prefix.add(partial != null ? "\\partial " + partial : "");
            }
            Frac target = length;
            if (!isPickup && length != null) {
                Frac g = lengths.get(m.index);
                target = nonZero(g) ? g : length;
                if (!target.equals(length)) {
                    prefix.add("\\set Timing.measureLength = " + measureLength(target));
                    boolean completesPickup = m == measures.get(measures.size() - 1) && nonZero(pickup)
                            && target.add(pickup).equals(length);
                    if (!completesPickup) {
                        prefix.add("%{ ?? rhythm: " + target.divide(4) + " of " + length.divide(4) + " %}");
                    }
                    overridden = true;
                } else if (overridden && m.time == null) {
                    prefix.add("\\set Timing.measureLength = " + measureLength(length));
                    overridden = false;
                } else {
                    overridden = false;
                }
            }
            String label = m.number != null && !m.number.isEmpty() ? m.number : Integer.toString(m.index + 1);
            lines.add("  % m. " + label);
            numbers.add(label);
            String body = measureMusic(m, target);
            if (target != null && own.signum() > 0 && own.lt(target) && !isPickup) {
                body += " " + String.join(" ", Notation.skips(target.subtract(own)));
            }
            if (leads.containsKey(m.index) && !opensWithGrace(m)) {
                body = graceSkip(leads.get(m.index)) + " " + body;
            }
            String suffix = barlineCommand(m.rightBarline, false);
            List<String> parts = new ArrayList<>();
            for (String t : prefix) {
                if (!t.isEmpty()) {
                    parts.add(t);
                }
            }
            if (!body.isEmpty()) {
                parts.add(body);
            }
            lines.add("  " + String.join(" ", parts) + " |" + (suffix != null && !suffix.isEmpty() ? " " + suffix : ""));
            numbers.add(label);
            Barline right = m.rightBarline;
            if (inEnding && right != null && ("stop".equals(right.endingType) || "discontinue".equals(right.endingType))) {
                closing = true;
            }
        }
        if (inEnding && !lines.isEmpty()) {
            int k = lines.size() - 1;
            lines.set(k, lines.get(k) + " " + voltaCommand(List.of("(volta #f)")));
        }
        return new StaffLines(lines, numbers);
    }

    // ---- lyrics ----

    /** Python {@code [^\W\d_]}: letters, and numeric characters that are not decimal digits. */
    public static boolean wordLetter(int cp) {
        int t = Character.getType(cp);
        return t == Character.UPPERCASE_LETTER || t == Character.LOWERCASE_LETTER || t == Character.TITLECASE_LETTER
                || t == Character.MODIFIER_LETTER || t == Character.OTHER_LETTER || t == Character.LETTER_NUMBER
                || t == Character.OTHER_NUMBER;
    }

    /** {@code ^[^\W\d_]+(?:['’][^\W\d_]+)*[.,;:!?]*$}: a bare lyric word. */
    static boolean plainSyllable(String text) {
        int[] cps = text.codePoints().toArray();
        int i = 0;
        int n = cps.length;
        int start = i;
        while (i < n && wordLetter(cps[i])) {
            i++;
        }
        if (i == start) {
            return false;
        }
        while (i < n && (cps[i] == '\'' || cps[i] == '’') && i + 1 < n && wordLetter(cps[i + 1])) {
            i++;
            while (i < n && wordLetter(cps[i])) {
                i++;
            }
        }
        while (i < n && ".,;:!?".indexOf(cps[i]) >= 0) {
            i++;
        }
        // Python's $ also matches before a final newline.
        return i == n || (i == n - 1 && cps[i] == '\n');
    }

    /** Quote anything LilyPond's lyric mode could misread. */
    private static String lyricToken(String text) {
        return plainSyllable(text) ? text : Notation.lilyString(text);
    }

    private static boolean allTieStop(Event e) {
        for (NoteHead h : e.notes) {
            if (!h.tieStop) {
                return false;
            }
        }
        return true;
    }

    /** Syllables of voice 1, one line per measure; null when the verse is empty. */
    private static List<String> lyricsLines(List<Measure> measures, int verse) {
        List<String> lines = new ArrayList<>();
        boolean found = false;
        for (Measure m : measures) {
            Voice voice = null;
            for (Voice v : m.voices) {
                if (voice == null || v.number < voice.number) {
                    voice = v;
                }
            }
            List<String> tokens = new ArrayList<>();
            for (Event e : voice != null ? voice.events : List.<Event>of()) {
                if (Event.REST.equals(e.kind) || e.grace || e.notes.isEmpty()) {
                    continue;
                }
                if (allTieStop(e)) {
                    continue; // tied continuation: no new syllable
                }
                Lyric lyric = null;
                for (Lyric ly : e.lyrics) {
                    if (ly.verse == verse) {
                        lyric = ly;
                        break;
                    }
                }
                if (lyric == null) {
                    tokens.add("_");
                    continue;
                }
                found = true;
                tokens.add(lyricToken(lyric.text));
                if ("begin".equals(lyric.syllabic) || "middle".equals(lyric.syllabic)) {
                    tokens.add("--");
                } else if (lyric.extend) {
                    tokens.add("__");
                }
            }
            lines.add(tokens.isEmpty() ? "" : "  " + String.join(" ", tokens));
        }
        if (!found) {
            return null;
        }
        // Drop trailing placeholders: notes after a verse's last syllable need none.
        while (!lines.isEmpty()) {
            String stripped = Py.rstrip(lines.get(lines.size() - 1));
            while (stripped.endsWith(" _") || Py.strip(stripped).equals("_")) {
                stripped = Py.rstrip(stripped.substring(0, stripped.length() - 1));
            }
            if (!Py.strip(stripped).isEmpty()) {
                lines.set(lines.size() - 1, stripped);
                break;
            }
            lines.remove(lines.size() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (!l.isEmpty()) {
                out.add(l);
            }
        }
        return out;
    }

    private static List<Integer> verses(List<Measure> measures) {
        TreeSet<Integer> out = new TreeSet<>();
        for (Measure m : measures) {
            for (Voice v : m.voices) {
                for (Event e : v.events) {
                    for (Lyric ly : e.lyrics) {
                        out.add(ly.verse);
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    // ---- chords ----

    private static final Frac CHORD_GRID = Frac.of(1, 32); // quarters: a 128th note

    private static Frac snap(Frac offset) {
        return Frac.of(offset.divide(CHORD_GRID).floor()).multiply(CHORD_GRID);
    }

    private static List<String> chordTokens(ChordSymbol c, Frac length) {
        List<String> durations = Notation.splitDuration(length);
        if (durations.isEmpty()) {
            durations = List.of("4");
        }
        String modifier = Notation.CHORD_MODIFIERS.getOrDefault(c.kind, "");
        String bass = c.bass != null && !c.bass.isEmpty() ? "/" + Notation.chordRoot(c.bass) : "";
        List<String> out = new ArrayList<>();
        out.add(Notation.chordRoot(c.root) + durations.get(0) + modifier + bass);
        for (String d : durations.subList(1, durations.size())) {
            out.add("s" + d);
        }
        return out;
    }

    /** One chordmode line per measure, from the first staff that has chord symbols. */
    private static List<String> chordLines(Score score, Map<Integer, List<String>> leads, Map<Integer, Frac> grid) {
        Staff staff = null;
        for (Score.PartStaff ps : score.staves()) {
            if (ps.staff().measures.stream().anyMatch(m -> !m.chordSymbols.isEmpty())) {
                staff = ps.staff();
                break;
            }
        }
        if (staff == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        Frac length = null;
        for (Measure m : staff.measures) {
            if (m.time != null) {
                length = m.time.measureLength();
            }
            Frac actual = longestVoice(m);
            Frac total;
            // A pickup measure only lasts as long as its notes.
            if (m.implicit && m.index == 0 && nonZero(actual)) {
                total = actual;
            } else {
                Frac g = grid.get(m.index);
                total = nonZero(g) ? g : nonZero(length) ? length : nonZero(actual) ? actual : Frac.of(4);
            }
            List<ChordSymbol> chords = new ArrayList<>(m.chordSymbols);
            chords.sort(Comparator.comparing(c -> c.offset));
            List<String> tokens = new ArrayList<>();
            if (leads.containsKey(m.index)) {
                tokens.add(graceSkip(leads.get(m.index)));
            }
            Frac position = Frac.ZERO;
            for (int k = 0; k < chords.size(); k++) {
                ChordSymbol c = chords.get(k);
                // Chord names sit on a 128th-note grid; skips absorb any remainder.
                Frac start = snap(c.offset).max(position);
                if (start.gt(position)) {
                    tokens.addAll(Notation.skips(start.subtract(position)));
                    position = start;
                }
                Frac end = k + 1 < chords.size() ? snap(chords.get(k + 1).offset) : snap(total);
                end = end.max(position.add(CHORD_GRID));
                tokens.addAll(chordTokens(c, end.subtract(position)));
                position = end;
            }
            if (position.lt(total)) {
                tokens.addAll(Notation.skips(total.subtract(position)));
            }
            String label = m.number != null && !m.number.isEmpty() ? m.number : Integer.toString(m.index + 1);
            lines.add("  " + String.join(" ", tokens) + " |  % m. " + label);
        }
        return lines;
    }

    // ---- project ----

    private static boolean isVocal(Part part) {
        return VOCAL.matcher(part.name == null ? "" : part.name).find()
                || VOCAL.matcher(part.abbreviation == null ? "" : part.abbreviation).find();
    }

    /** The project's names for each part (Python {@code _plan}), as the generator assigns them. */
    public static List<PartVars> plan(Score score) {
        Set<String> usedIds = new HashSet<>();
        Set<String> usedSlugs = new HashSet<>();
        List<PartVars> plans = new ArrayList<>();
        int k = 0;
        for (Part part : score.parts) {
            k++;
            String label = part.name != null && !part.name.isEmpty() ? part.name : "Part " + k;
            String base = unique(identifier(label), usedIds, "");
            String slug = unique(slug(label), usedSlugs, "-");
            PartVars pv = new PartVars(part, slug, base);
            for (Staff staff : part.staves) {
                String suffix = staffSuffix(staff.number, part.staves.size());
                StaffVars sv = new StaffVars(base + suffix + "Music", base + suffix + "Voice");
                for (int verse : verses(staff.measures)) {
                    sv.lyrics.add(base + suffix + "Verse" + NUMBER_WORDS[Math.floorMod(verse, 10)]);
                }
                pv.staves.add(sv);
            }
            plans.add(pv);
        }
        return plans;
    }

    private static String partFile(PartVars pv, String rel, Map<Line, MeasureRef> lineMap,
            Map<Integer, List<String>> leads, Map<Integer, Frac> grid, Map<StaffRef, List<String>> staffNotes) {
        List<String> out = new ArrayList<>(List.of("\\version \"" + VERSION + "\"", ""));
        for (int s = 0; s < pv.part.staves.size(); s++) {
            Staff staff = pv.part.staves.get(s);
            StaffVars sv = pv.staves.get(s);
            out.add(sv.music + " = {");
            for (String note : staffNotes.getOrDefault(new StaffRef(pv.part.id, staff.number), List.of())) {
                out.add("  " + note);
            }
            if (pv.part.transposeSemitones != null && pv.part.transposeSemitones != 0) {
                out.add("  \\transposition " + Notation.transpositionPitch(pv.part.transposeSemitones));
            }
            StaffLines body = staffMusic(staff.measures, leads, grid);
            for (int i = 0; i < body.lines().size(); i++) {
                String text = body.lines().get(i);
                out.add(text);
                if (!lstripPy(text).startsWith("%")) {
                    lineMap.put(new Line(rel, out.size()), new MeasureRef(pv.part.id, staff.number, body.numbers().get(i)));
                }
            }
            out.add("}");
            out.add("");
            List<Integer> vs = verses(staff.measures);
            for (int j = 0; j < sv.lyrics.size(); j++) {
                List<String> lines = lyricsLines(staff.measures, vs.get(j));
                if (lines == null) {
                    continue;
                }
                out.add(sv.lyrics.get(j) + " = \\lyricmode {");
                out.addAll(lines);
                out.add("}");
                out.add("");
            }
        }
        return Py.rstrip(String.join("\n", out)) + "\n";
    }

    private static String lstripPy(String s) {
        int i = 0;
        while (i < s.length() && Py.isSpace(s.codePointAt(i))) {
            i += Character.charCount(s.codePointAt(i));
        }
        return s.substring(i);
    }

    /** Characters of an instrument name that fit LilyPond's default indent on one line. */
    private static final int NAME_LINE = 10;
    private static final Pattern ROLE = Pattern.compile("\\s*\\(.*\\)\\s*$", Pattern.UNICODE_CHARACTER_CLASS);

    /** An instrument name as LilyPond takes it: a string, or, when too long for the indent before
     *  the first system, its words wrapped into a centred column. */
    static String instrumentName(String text) {
        List<String> lines = new ArrayList<>();
        for (String word : Py.split(text)) {
            if (!lines.isEmpty() && Py.len(lines.get(lines.size() - 1)) + 1 + Py.len(word) <= NAME_LINE) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + " " + word);
            } else {
                lines.add(word);
            }
        }
        if (lines.size() <= 1) {
            return Notation.lilyString(text);
        }
        List<String> quoted = new ArrayList<>();
        for (String l : lines) {
            quoted.add(Notation.lilyString(l));
        }
        return "\\markup \\center-column { " + String.join(" ", quoted) + " }";
    }

    /** A short instrument name without its role in parentheses. */
    static String shortName(String text) {
        String s = ROLE.matcher(text).replaceAll("");
        return s.isEmpty() ? text : s;
    }

    private static int verseNumber(String var) {
        for (int k = 0; k < NUMBER_WORDS.length; k++) {
            if (var.endsWith("Verse" + NUMBER_WORDS[k])) {
                return k;
            }
        }
        return 1;
    }

    private static List<String> staffBlock(PartVars pv, String indent, boolean withNames) {
        Part part = pv.part;
        String name = part.name == null ? "" : part.name;
        String shortN = shortName(part.abbreviation == null ? "" : part.abbreviation);
        List<String> lines = new ArrayList<>();
        boolean multi = pv.staves.size() > 1;
        String inner = multi ? indent + "  " : indent;
        if (multi) {
            lines.add(indent + "\\new PianoStaff \\with {");
            if (withNames && !name.isEmpty()) {
                lines.add(indent + "  instrumentName = " + instrumentName(name));
            }
            if (withNames && !shortN.isEmpty()) {
                lines.add(indent + "  shortInstrumentName = " + instrumentName(shortN));
            }
            lines.add(indent + "} <<");
        }
        for (int s = 0; s < part.staves.size(); s++) {
            Staff staff = part.staves.get(s);
            StaffVars sv = pv.staves.get(s);
            List<String> with = new ArrayList<>();
            if (!multi && withNames && !name.isEmpty()) {
                with.add("instrumentName = " + instrumentName(name));
            }
            if (!multi && withNames && !shortN.isEmpty()) {
                with.add("shortInstrumentName = " + instrumentName(shortN));
            }
            String opening = inner + "\\new Staff";
            if (!with.isEmpty()) {
                opening += " \\with { " + String.join(" ", with) + " }";
            }
            lines.add(opening + " \\new Voice = \"" + sv.voice + "\" \\" + sv.music);
            for (String verseVar : sv.lyrics) {
                if (lyricsLines(staff.measures, verseNumber(verseVar)) != null) {
                    lines.add(inner + "\\new Lyrics \\lyricsto \"" + sv.voice + "\" \\" + verseVar);
                }
            }
        }
        if (multi) {
            lines.add(indent + ">>");
        }
        return lines;
    }

    private static String scoreLayout(List<PartVars> plans, boolean hasChords) {
        List<String> out = new ArrayList<>(List.of("\\version \"" + VERSION + "\"", "", "\\score {", "  <<"));
        if (hasChords) {
            out.add("    \\new ChordNames \\harmonies");
        }
        String group = null;
        if (plans.size() > 1) {
            group = plans.stream().allMatch(p -> isVocal(p.part)) ? "ChoirStaff" : "StaffGroup";
            out.add("    \\new " + group + " <<");
        }
        String indent = group != null ? "      " : "    ";
        for (PartVars pv : plans) {
            out.addAll(staffBlock(pv, indent, true));
        }
        if (group != null) {
            out.add("    >>");
        }
        out.addAll(List.of("  >>", "  \\layout { }", "  \\midi { }", "}", ""));
        return String.join("\n", out);
    }

    private static String partLayout(PartVars pv, boolean hasChords) {
        List<String> out = new ArrayList<>(List.of("\\version \"" + VERSION + "\"", "",
                "\\include \"../parts/" + pv.slug + ".ly\""));
        if (hasChords) {
            out.add("\\include \"../chords.ly\"");
        }
        out.addAll(List.of("", "\\score {", "  <<"));
        if (hasChords) {
            out.add("    \\new ChordNames \\harmonies");
        }
        out.addAll(staffBlock(pv, "    ", true));
        out.addAll(List.of("  >>", "  \\layout { }", "}", ""));
        return String.join("\n", out);
    }

    /** The LilyPond project for {@code score}. */
    public static LyProject generateProject(Score score) {
        List<PartVars> plans = plan(score);
        Map<Integer, List<String>> leads = graceLeads(score);
        Map<Integer, Frac> grid = measureGrid(score);
        Map<String, String> files = new HashMap<>();
        Map<Line, MeasureRef> lineMap = new LinkedHashMap<>();
        Map<StaffRef, List<String>> notes = staffNotes(score);
        for (PartVars pv : plans) {
            String rel = "parts/" + pv.slug + ".ly";
            files.put(rel, partFile(pv, rel, lineMap, leads, grid, notes));
        }
        List<String> chordLines = chordLines(score, leads, grid);
        boolean hasChords = chordLines != null && !chordLines.isEmpty();
        if (hasChords) {
            List<String> c = new ArrayList<>(List.of("\\version \"" + VERSION + "\"", "", "harmonies = \\chordmode {"));
            c.addAll(chordLines);
            c.addAll(List.of("}", ""));
            files.put("chords.ly", String.join("\n", c));
        }
        files.put("layout/score.ly", scoreLayout(plans, hasChords));
        for (PartVars pv : plans) {
            files.put("layout/part-" + pv.slug + ".ly", partLayout(pv, hasChords));
        }
        List<String> header = new ArrayList<>(List.of("\\header {"));
        if (score.title != null && !score.title.isEmpty()) {
            header.add("  title = " + Notation.lilyString(score.title));
        }
        if (score.composer != null && !score.composer.isEmpty()) {
            header.add("  composer = " + Notation.lilyString(score.composer));
        }
        header.addAll(List.of("  tagline = ##f", "}"));
        List<String> main = new ArrayList<>(List.of("\\version \"" + VERSION + "\"", ""));
        main.addAll(header);
        main.add("");
        for (PartVars pv : plans) {
            main.add("\\include \"parts/" + pv.slug + ".ly\"");
        }
        if (hasChords) {
            main.add("\\include \"chords.ly\"");
        }
        main.addAll(List.of("\\include \"layout/score.ly\"", ""));
        files.put("main.ly", String.join("\n", main));
        Map<String, StaffRef> staffVars = new LinkedHashMap<>();
        for (PartVars pv : plans) {
            for (int s = 0; s < pv.part.staves.size(); s++) {
                staffVars.put(pv.staves.get(s).music, new StaffRef(pv.part.id, pv.part.staves.get(s).number));
            }
        }
        // dict(sorted(files.items())): Python sorts str keys by code point, as TreeMap does here
        // for the ASCII paths the generator makes.
        return new LyProject(Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(files))),
                lineMap, staffVars);
    }

    public static LyProject writeProject(Score score, Path root) {
        LyProject project = generateProject(score);
        project.write(root);
        return project;
    }
}

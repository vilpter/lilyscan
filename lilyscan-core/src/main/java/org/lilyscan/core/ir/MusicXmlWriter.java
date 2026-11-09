package org.lilyscan.core.ir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.lilyscan.core.xml.EtWriter;
import org.lilyscan.core.xml.EtWriter.El;

/**
 * IR to MusicXML (partwise 4.0), port of {@code lilyscan/ir/to_musicxml.py}: the inverse of
 * {@link MusicXml}, byte-identical to the Python writer.
 */
public final class MusicXmlWriter {
    private MusicXmlWriter() {}

    private static final Map<String, Integer> DEFAULT_LINE = Map.of("G", 2, "F", 4, "C", 3);
    private static final Map<Integer, String> ACCIDENTALS =
            Map.of(2, "double-sharp", 1, "sharp", 0, "natural", -1, "flat", -2, "flat-flat");
    private static final Map<Frac, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put(Frac.of(4), "whole");
        TYPES.put(Frac.of(2), "half");
        TYPES.put(Frac.of(1), "quarter");
        TYPES.put(Frac.of(1, 2), "eighth");
        TYPES.put(Frac.of(1, 4), "16th");
        TYPES.put(Frac.of(1, 8), "32nd");
        TYPES.put(Frac.of(1, 16), "64th");
        TYPES.put(Frac.of(8), "breve");
    }
    private static final Pattern SAFE_TAG =
            Pattern.compile("^[A-Za-z][\\w-]*$", Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
    /** Diatonic steps of the default spelling of 0-11 semitones (P1 m2 M2 m3 M3 P4 A4 P5 m6 M6 m7 M7). */
    private static final int[] STEPS_BY_SEMITONES = {0, 1, 1, 2, 2, 3, 3, 4, 5, 5, 6, 6};
    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
            + "<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML 4.0 Partwise//EN\" "
            + "\"http://www.musicxml.org/dtds/partwise.dtd\">\n";

    private static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }

    private static long lcm(long a, long b) {
        long x = a, y = b;
        while (y != 0) {
            long t = x % y;
            x = y;
            y = t;
        }
        return a / x * b;
    }

    /** Divisions per quarter note: every offset and duration a whole number of them. */
    static long divisions(Score score) {
        TreeSet<Long> dens = new TreeSet<>();
        dens.add(1L);
        for (Score.PartStaff ps : score.staves()) {
            for (Measure m : ps.staff().measures) {
                for (Voice v : m.voices) {
                    for (Event e : v.events) {
                        dens.add(e.offset.denominator());
                        dens.add(e.duration.denominator());
                    }
                }
                m.clefs.forEach(c -> dens.add(c.offset.denominator()));
                m.chordSymbols.forEach(c -> dens.add(c.offset.denominator()));
                m.directions.forEach(d -> dens.add(d.offset.denominator()));
            }
        }
        long out = 1;
        for (long d : dens) out = lcm(out, d);
        return out;
    }

    private static long ticks(Frac f, long divisions) {
        return f.multiply(divisions).truncate();
    }

    private record NoteType(String type, int dots) {}

    private static NoteType noteType(Event e) {
        if (truthy(e.noteType)) return new NoteType(e.noteType, e.dots);
        Frac written = e.duration;
        if (e.tuplet != null) written = written.multiply(e.tuplet.actual()).divide(e.tuplet.normal());
        Frac[] factors = {Frac.ONE, Frac.of(3, 2), Frac.of(7, 4)};
        for (int dots = 0; dots < 3; dots++) {
            String t = TYPES.get(written.divide(factors[dots]));
            if (t != null) return new NoteType(t, dots);
        }
        return new NoteType(null, 0);
    }

    private record StaffVoice(int staff, int voice) {}

    private static Map<StaffVoice, Integer> voices(List<Measure> measures, List<Integer> staffNumbers) {
        Map<Integer, Integer> taken = new HashMap<>();
        Map<StaffVoice, Integer> out = new HashMap<>();
        for (int k = 0; k < measures.size(); k++) {
            int staff = staffNumbers.get(k);
            Measure m = measures.get(k);
            TreeSet<Integer> numbers = new TreeSet<>();
            if (m != null) {
                for (Voice v : m.voices) if (!v.events.isEmpty()) numbers.add(v.number);
            }
            for (int n : numbers) {
                int written = n;
                while (taken.containsKey(written) && taken.get(written) != staff) written += 4;
                taken.put(written, staff);
                out.put(new StaffVoice(staff, n), written);
            }
        }
        return out;
    }

    private static void clef(El parent, Clef clef, Integer staff) {
        El el = staff != null && staff != 0 ? parent.sub("clef", null, "number", staff.toString()) : parent.sub("clef");
        el.sub("sign", clef.sign);
        Integer line = clef.line != null && clef.line != 0 ? clef.line : DEFAULT_LINE.get(clef.sign);
        if (line != null && DEFAULT_LINE.containsKey(clef.sign)) el.sub("line", line);
        if (clef.octaveChange != 0) el.sub("clef-octave-change", clef.octaveChange);
    }

    private static void barline(El parent, Barline b, String location) {
        El el = parent.sub("barline", null, "location", location);
        if (truthy(b.style) && !b.style.equals("regular")) el.sub("bar-style", b.style);
        if (truthy(b.ending)) {
            String type = truthy(b.endingType) ? b.endingType : location.equals("left") ? "start" : "stop";
            el.sub("ending", null, "number", b.ending, "type", type);
        }
        if (truthy(b.repeat)) el.sub("repeat", null, "direction", b.repeat);
    }

    private static String rootStep(String name) {
        return name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
    }

    private static int rootAlter(String name) {
        String rest = name.substring(1);
        return (int) (rest.chars().filter(c -> c == '#').count() - rest.chars().filter(c -> c == 'b').count());
    }

    private static void harmony(El parent, ChordSymbol c, long divisions) {
        El el = parent.sub("harmony");
        El root = el.sub("root");
        root.sub("root-step", rootStep(c.root));
        if (rootAlter(c.root) != 0) root.sub("root-alter", rootAlter(c.root));
        El kind = el.sub("kind", c.kind);
        if (truthy(c.text)) kind.set("text", c.text);
        if (truthy(c.bass)) {
            El bass = el.sub("bass");
            bass.sub("bass-step", rootStep(c.bass));
            if (rootAlter(c.bass) != 0) bass.sub("bass-alter", rootAlter(c.bass));
        }
        if (!c.offset.isZero()) el.sub("offset", ticks(c.offset, divisions));
    }

    /** event -> "start" or "stop" for the brackets of runs of tuplet notes. */
    private static Map<Event, String> tupletMarks(List<Event> events) {
        Map<Event, String> marks = new IdentityHashMap<>();
        List<Event> run = new ArrayList<>();
        Frac span = Frac.ZERO, filled = Frac.ZERO;
        for (Event e : events) {
            if (e.tuplet == null || e.grace) {
                close(run, marks);
                run = new ArrayList<>();
                filled = Frac.ZERO;
                continue;
            }
            if (run.isEmpty() || !run.get(0).tuplet.equals(e.tuplet) || filled.ge(span)) {
                close(run, marks);
                int actual = e.tuplet.actual(), normal = e.tuplet.normal();
                span = e.duration.multiply(actual).divide(normal).multiply(normal);
                run = new ArrayList<>();
                filled = Frac.ZERO;
            }
            run.add(e);
            filled = filled.add(e.duration);
        }
        close(run, marks);
        return marks;
    }

    private static void close(List<Event> run, Map<Event, String> marks) {
        if (!run.isEmpty()) {
            marks.put(run.get(0), "start");
            marks.put(run.get(run.size() - 1), run.size() > 1 ? "stop" : "start");
        }
    }

    private static void note(El parent, Event e, NoteHead head, boolean chord, int voice, Integer staff,
            long divisions, String tupletMark, List<String> beams) {
        El el = parent.sub("note");
        if (e.grace) el.sub("grace");
        if (chord) el.sub("chord");
        if (head == null) {
            if (e.measureRest) el.sub("rest", null, "measure", "yes"); else el.sub("rest");
        } else {
            El pitch = el.sub("pitch");
            pitch.sub("step", head.pitch.step);
            if (head.pitch.alter != 0) pitch.sub("alter", head.pitch.alter);
            pitch.sub("octave", head.pitch.octave);
        }
        if (!e.grace) el.sub("duration", ticks(e.duration, divisions));
        if (head != null && head.tieStop) el.sub("tie", null, "type", "stop");
        if (head != null && head.tieStart) el.sub("tie", null, "type", "start");
        el.sub("voice", voice);
        NoteType nt = noteType(e);
        if (nt.type() != null && !(head == null && e.measureRest)) {
            el.sub("type", nt.type());
            for (int i = 0; i < nt.dots(); i++) el.sub("dot");
        }
        if (head != null && head.accidentalShown) {
            el.sub("accidental", ACCIDENTALS.getOrDefault(head.pitch.alter, "natural"));
        }
        if (e.tuplet != null) {
            El tm = el.sub("time-modification");
            tm.sub("actual-notes", e.tuplet.actual());
            tm.sub("normal-notes", e.tuplet.normal());
        }
        if (staff != null) el.sub("staff", staff);
        if (beams != null && !chord) {
            for (int b = 0; b < beams.size(); b++) el.sub("beam", beams.get(b), "number", Integer.toString(b + 1));
        }
        El notations = new El("notations");
        if (head != null && head.tieStop) notations.sub("tied", null, "type", "stop");
        if (head != null && head.tieStart) notations.sub("tied", null, "type", "start");
        if (tupletMark != null && !chord) notations.sub("tuplet", null, "type", tupletMark);
        if (!chord) {
            for (Slur sl : e.slurs) notations.sub("slur", null, "type", sl.type, "number", Integer.toString(sl.number));
        }
        List<String> marks = e.articulations.stream()
                .filter(a -> !a.equals("fermata") && !Event.ORNAMENTS.contains(a) && SAFE_TAG.matcher(a).find()).toList();
        if (!marks.isEmpty() && !chord) {
            List<String> arts = marks.stream().filter(a -> !Event.BOWINGS.contains(a)).toList();
            if (!arts.isEmpty()) {
                El a = notations.sub("articulations");
                arts.forEach(a::sub);
            }
            List<String> bowings = marks.stream().filter(Event.BOWINGS::contains).toList();
            if (!bowings.isEmpty()) {
                El t = notations.sub("technical");
                bowings.forEach(t::sub);
            }
        }
        List<String> ornaments = e.articulations.stream().filter(Event.ORNAMENTS::contains).toList();
        if (!ornaments.isEmpty() && !chord) {
            El o = notations.sub("ornaments");
            ornaments.forEach(o::sub);
        }
        if (e.articulations.contains("fermata") && !chord) notations.sub("fermata");
        if (!notations.children.isEmpty()) el.children.add(notations);
        if (!chord) {
            for (Lyric ly : e.lyrics) {
                El lyric = el.sub("lyric", null, "number", Integer.toString(ly.verse));
                lyric.sub("syllabic", ly.syllabic);
                lyric.sub("text", ly.text);
                if (ly.extend) lyric.sub("extend");
            }
        }
    }

    private static void forward(El m, Frac by, long divisions, int voice, Integer staff) {
        El fwd = m.sub("forward");
        fwd.sub("duration", ticks(by, divisions));
        fwd.sub("voice", voice);
        if (staff != null && staff != 0) fwd.sub("staff", staff);
    }

    private record Stream(Staff staff, Measure measure, Voice voice) {}

    private static void writeMeasure(El partEl, Part part, int i, long divisions, boolean first, Options options) {
        List<Staff> staves = part.staves;
        List<Measure> measures = new ArrayList<>();
        for (Staff st : staves) measures.add(i < st.measures.size() ? st.measures.get(i) : null);
        Measure lead = measures.stream().filter(m -> m != null).findFirst().orElseThrow();
        String number = truthy(lead.number) ? lead.number : lead.implicit ? Integer.toString(i) : Integer.toString(i + 1);
        El mEl = lead.implicit ? partEl.sub("measure", null, "number", number, "implicit", "yes")
                : partEl.sub("measure", null, "number", number);
        boolean multi = staves.size() > 1;
        Map<StaffVoice, Integer> voices = voices(measures, staves.stream().map(s -> s.number).toList());

        El attrs = new El("attributes");
        if (first) attrs.sub("divisions", divisions);
        if (lead.key != null) {
            El key = attrs.sub("key");
            key.sub("fifths", lead.key.fifths);
            if (truthy(lead.key.mode)) key.sub("mode", lead.key.mode);
        }
        if (lead.time != null) {
            El time = "normal".equals(lead.time.symbol) ? attrs.sub("time")
                    : attrs.sub("time", null, "symbol", lead.time.symbol);
            time.sub("beats", lead.time.beats);
            time.sub("beat-type", lead.time.beatType);
        }
        if (first && multi) attrs.sub("staves", staves.size());
        for (int k = 0; k < staves.size(); k++) {
            Measure m = measures.get(k);
            if (m == null) continue;
            for (Clef c : m.clefs) {
                if (c.offset.isZero()) clef(attrs, c, multi ? staves.get(k).number : null);
            }
        }
        if (first && part.transposeSemitones != null && part.transposeSemitones != 0) {
            int semis = part.transposeSemitones;
            int octaves = Math.abs(semis) / 12, chromatic = Math.abs(semis) % 12;
            int sign = semis < 0 ? -1 : 1;
            El tr = attrs.sub("transpose");
            tr.sub("diatonic", sign * STEPS_BY_SEMITONES[chromatic]);
            tr.sub("chromatic", sign * chromatic);
            if (octaves != 0) tr.sub("octave-change", sign * octaves);
        }
        if (!attrs.children.isEmpty()) mEl.children.add(attrs);

        if (lead.leftBarline != null) barline(mEl, lead.leftBarline, "left");
        List<ChordSymbol> harmonies = new ArrayList<>(lead.chordSymbols);
        if (!options.inlineHarmony()) {
            for (ChordSymbol c : harmonies) harmony(mEl, c, divisions);
            harmonies.clear();
        }

        for (int k = 0; k < staves.size(); k++) {
            Measure m = measures.get(k);
            if (m == null) continue;
            for (Direction d : m.directions) direction(mEl, d, divisions, multi ? staves.get(k).number : null);
        }

        List<Stream> streams = new ArrayList<>();
        for (int k = 0; k < staves.size(); k++) {
            Measure m = measures.get(k);
            if (m == null) continue;
            List<Voice> sorted = new ArrayList<>(m.voices);
            sorted.sort(Comparator.comparingInt(v -> v.number));
            for (Voice v : sorted) if (!v.events.isEmpty()) streams.add(new Stream(staves.get(k), m, v));
        }
        for (int k = 0; k < streams.size(); k++) {
            Stream s = streams.get(k);
            Integer staffNo = multi ? s.staff().number : null;
            int voiceNo = voices.get(new StaffVoice(s.staff().number, s.voice().number));
            List<Event> events = new ArrayList<>(s.voice().events);
            events.sort(Comparator.<Event, Frac>comparing(e -> e.offset).thenComparing(e -> !e.grace));
            Voice firstVoice = null;
            for (Voice x : s.measure().voices) {
                if (!x.events.isEmpty() && (firstVoice == null || x.number < firstVoice.number)) firstVoice = x;
            }
            List<Clef> pending = new ArrayList<>();
            if (s.voice() == firstVoice) {
                for (Clef c : s.measure().clefs) if (c.offset.signum() > 0) pending.add(c);
            }
            Map<Event, String> marks = tupletMarks(events);
            Map<Event, List<String>> beams = options.beams() ? beams(events, lead.time != null ? lead.time : time(s.staff(), i)) : Map.of();
            Frac cursor = Frac.ZERO;
            for (Event e : events) {
                if (e.offset.gt(cursor)) {
                    forward(mEl, e.offset.subtract(cursor), divisions, voiceNo, staffNo);
                    cursor = e.offset;
                }
                // Inline chord symbols go before the first note at or after them, in the first stream.
                while (k == 0 && !harmonies.isEmpty() && !e.grace && harmonies.get(0).offset.le(e.offset)) {
                    ChordSymbol c = harmonies.remove(0);
                    harmony(mEl, new ChordSymbol(c.offset.subtract(e.offset), c.root, c.kind, c.bass, c.text), divisions);
                }
                while (!pending.isEmpty() && pending.get(0).offset.le(cursor)) {
                    clef(mEl.sub("attributes"), pending.remove(0), staffNo);
                }
                List<NoteHead> heads = new ArrayList<>(e.notes);
                if (heads.isEmpty()) heads.add(null);
                for (int n = 0; n < heads.size(); n++) {
                    note(mEl, e, heads.get(n), n > 0, voiceNo, staffNo, divisions, marks.get(e), beams.get(e));
                }
                if (!e.grace) cursor = cursor.add(e.duration);
            }
            for (Clef c : pending) {
                if (c.offset.gt(cursor)) {
                    forward(mEl, c.offset.subtract(cursor), divisions, voiceNo, staffNo);
                    cursor = c.offset;
                }
                clef(mEl.sub("attributes"), c, staffNo);
            }
            if (k == 0) {
                for (ChordSymbol c : harmonies) {
                    harmony(mEl, new ChordSymbol(c.offset.subtract(cursor), c.root, c.kind, c.bass, c.text), divisions);
                }
                harmonies.clear();
            }
            if (k < streams.size() - 1 && cursor.signum() > 0) {
                mEl.sub("backup").sub("duration", ticks(cursor, divisions));
            }
        }
        for (ChordSymbol c : harmonies) harmony(mEl, c, divisions); // a measure without notes
        if (lead.rightBarline != null) barline(mEl, lead.rightBarline, "right");
    }

    /**
     * A direction, written before the measure's notes and placed by {@code <offset>} (the
     * importer reads it back the same way).
     */
    private static void direction(El mEl, Direction d, long divisions, Integer staffNo) {
        El type = new El("direction-type");
        switch (d.kind) {
            case Direction.OCTAVE_SHIFT, Direction.WEDGE -> {
                List<String> attrs = new ArrayList<>(List.of("type", d.type));
                if (d.number != null) attrs.addAll(List.of("number", Integer.toString(d.number)));
                if (d.size != null) attrs.addAll(List.of("size", Integer.toString(d.size)));
                type.sub(d.kind, null, attrs.toArray(String[]::new));
            }
            case Direction.DYNAMICS -> {
                El dyn = type.sub("dynamics");
                if (SAFE_TAG.matcher(d.text).find() && !d.text.contains(" ")) dyn.sub(d.text);
                else dyn.sub("other-dynamics", d.text);
            }
            case Direction.WORDS -> type.sub("words", d.text);
            case Direction.METRONOME -> {
                El met = type.sub("metronome");
                String unit = d.type == null ? "quarter" : d.type;
                met.sub("beat-unit", unit.replace(".", ""));
                for (int k = 0; k < unit.length() - unit.replace(".", "").length(); k++) met.sub("beat-unit-dot");
                met.sub("per-minute", d.text);
            }
            default -> {
                return;
            }
        }
        El dEl = d.placement != null ? mEl.sub("direction", null, "placement", d.placement) : mEl.sub("direction");
        dEl.children.add(type);
        if (d.offset.signum() > 0) dEl.sub("offset", ticks(d.offset, divisions));
        if (staffNo != null) dEl.sub("staff", staffNo);
    }

    /** The meter in force at measure {@code i} of a staff. */
    private static TimeSignature time(Staff staff, int i) {
        for (int j = Math.min(i, staff.measures.size() - 1); j >= 0; j--) {
            if (staff.measures.get(j).time != null) return staff.measures.get(j).time;
        }
        return null;
    }

    private static int flags(String type) {
        return switch (type == null ? "" : type) {
            case "eighth" -> 1;
            case "16th" -> 2;
            case "32nd" -> 3;
            case "64th" -> 4;
            default -> 0;
        };
    }

    /**
     * Beams by beat, as music21's {@code makeBeams} draws them: notes shorter than a quarter are
     * beamed together within a beat (a dotted quarter in compound meters), not across rests; the
     * second and further beams join the notes that have them, and a lone one gets a hook.
     */
    public static Map<Event, List<String>> beams(List<Event> events, TimeSignature time) {
        Frac beat = time == null ? Frac.ONE : Frac.of(4, time.beatType);
        if (time != null && time.beatType == 8 && time.beats % 3 == 0 && time.beats > 3) beat = Frac.of(3, 2);
        Map<Event, List<String>> out = new java.util.HashMap<>();
        List<Event> run = new ArrayList<>();
        long runBeat = Long.MIN_VALUE;
        List<Event> all = new ArrayList<>();
        for (Event e : events) if (!e.grace) all.add(e);
        for (int i = 0; i <= all.size(); i++) {
            Event e = i < all.size() ? all.get(i) : null;
            boolean beamable = e != null && !e.isRest() && flags(noteType(e).type()) > 0;
            long b = e == null ? Long.MIN_VALUE : e.offset.divide(beat).floor();
            boolean fits = e != null && e.offset.add(e.duration).le(Frac.of(b).multiply(beat).add(beat));
            if (!beamable || !fits || b != runBeat) {
                if (run.size() > 1) mark(run, out);
                run.clear();
            }
            if (beamable && fits) {
                run.add(e);
                runBeat = b;
            }
        }
        return out;
    }

    private static void mark(List<Event> run, Map<Event, List<String>> out) {
        int max = 0;
        for (Event e : run) max = Math.max(max, flags(noteType(e).type()));
        for (Event e : run) out.put(e, new ArrayList<>());
        for (int level = 1; level <= max; level++) {
            for (int i = 0; i < run.size(); i++) {
                int f = flags(noteType(run.get(i)).type());
                if (f < level) continue;
                boolean prev = i > 0 && flags(noteType(run.get(i - 1)).type()) >= level;
                boolean next = i < run.size() - 1 && flags(noteType(run.get(i + 1)).type()) >= level;
                String value = level == 1 ? (i == 0 ? "begin" : i == run.size() - 1 ? "end" : "continue")
                        : prev && next ? "continue" : prev ? "end" : next ? "begin"
                        : i == run.size() - 1 ? "backward hook" : "forward hook";
                out.get(run.get(i)).add(value);
            }
        }
    }

    /**
     * Writing choices beyond Python's {@code score.musicxml} export (which the defaults reproduce).
     * {@code inlineHarmony}: chord symbols placed before the note they sound with, instead of at
     * an {@code <offset>} (musicxml2ly ignores offsets). {@code beams}: {@code <beam>} elements by
     * beat, as music21 writes them (musicxml2ly otherwise engraves flags). The corpus builder's
     * ground truth uses both.
     */
    public record Options(boolean inlineHarmony, boolean beams) {
        public static final Options DEFAULT = new Options(false, false);
        public static final Options ENGRAVING = new Options(true, true);
    }

    public static String toMusicXml(Score score) {
        return toMusicXml(score, Options.DEFAULT);
    }

    public static String toMusicXml(Score score, Options options) {
        El root = new El("score-partwise").set("version", "4.0");
        if (truthy(score.title)) root.sub("work").sub("work-title", score.title);
        El ident = root.sub("identification");
        if (truthy(score.composer)) ident.sub("creator", score.composer, "type", "composer");
        ident.sub("encoding").sub("software", "Lilyscan");
        El partList = root.sub("part-list");
        long divisions = divisions(score);
        for (int k = 0; k < score.parts.size(); k++) {
            Part part = score.parts.get(k);
            El sp = partList.sub("score-part", null, "id", "P" + (k + 1));
            sp.sub("part-name", part.name == null ? "" : part.name);
            if (truthy(part.abbreviation)) sp.sub("part-abbreviation", part.abbreviation);
        }
        for (int k = 0; k < score.parts.size(); k++) {
            Part part = score.parts.get(k);
            El partEl = root.sub("part", null, "id", "P" + (k + 1));
            int count = part.staves.stream().mapToInt(st -> st.measures.size()).max().orElse(0);
            for (int i = 0; i < count; i++) writeMeasure(partEl, part, i, divisions, i == 0, options);
        }
        EtWriter.indent(root, "  ");
        return HEADER + EtWriter.toString(root) + "\n";
    }

    public static Path write(Score score, Path path) {
        return write(score, path, Options.DEFAULT);
    }

    public static Path write(Score score, Path path, Options options) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, toMusicXml(score, options), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }
}

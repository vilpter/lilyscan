package org.lilyscan.core.ir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.lilyscan.core.util.Py;
import org.lilyscan.core.xml.Xml;
import org.w3c.dom.Element;

/**
 * MusicXML (partwise, {@code .musicxml}/{@code .xml}/{@code .mxl}) -> IR.
 *
 * <p>Port of {@code lilyscan/ir/musicxml.py}. Used for Audiveris output, ground truth, and any
 * second engine. The reader keeps staff and voice numbers exactly as written, so comparisons see
 * what the engine produced rather than a re-interpretation.
 */
public final class MusicXml {
    private MusicXml() {}

    /** The provenance rule on the measures the reader adds for a multi-measure rest written as
     *  one measure: they have no place of their own on the page. */
    public static final String MULTI_REST = "multi-rest";

    /** A MusicXML document the reader cannot read. */
    public static final class MusicXmlException extends IllegalArgumentException {
        public MusicXmlException(String message) {
            super(message);
        }
    }

    /** A measure the reader added for a multi-measure rest written as one measure. */
    public static boolean addedRest(Measure measure) {
        boolean any = false;
        for (Voice v : measure.voices) {
            for (Event e : v.events) {
                any = true;
                boolean rule = false;
                for (Provenance p : e.provenance) {
                    if (MULTI_REST.equals(p.rule)) {
                        rule = true;
                        break;
                    }
                }
                if (!rule) {
                    return false;
                }
            }
        }
        return any;
    }

    /** Each measure's index among the measures the file wrote: the measures added for a
     *  multi-measure rest share the index of the one holding the rest. */
    public static List<Integer> writtenMeasures(List<Measure> measures) {
        List<Integer> out = new ArrayList<>();
        int k = -1;
        for (Measure m : measures) {
            if (!addedRest(m)) {
                k += 1;
            }
            out.add(Math.max(k, 0));
        }
        return out;
    }

    /** The score document, unpacking {@code .mxl} containers. */
    public static byte[] readBytes(Path path) {
        try {
            byte[] data = Files.readAllBytes(path);
            ZipFile z;
            try {
                z = new ZipFile(path.toFile());
            } catch (ZipException e) {
                return data;
            }
            try (z) {
                List<String> names = new ArrayList<>();
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    names.add(en.nextElement().getName());
                }
                String rootName = null;
                if (names.contains("META-INF/container.xml")) {
                    Element container = Xml.parse(read(z, "META-INF/container.xml")).getDocumentElement();
                    for (Element el : Xml.iter(container)) {
                        String full = Xml.attr(el, "full-path");
                        if (Xml.tag(el).endsWith("rootfile") && full != null && !full.isEmpty()) {
                            rootName = full;
                            break;
                        }
                    }
                }
                if (rootName == null) {
                    List<String> candidates = new ArrayList<>();
                    for (String n : names) {
                        if ((n.endsWith(".xml") || n.endsWith(".musicxml")) && !n.startsWith("META-INF/")) {
                            candidates.add(n);
                        }
                    }
                    if (candidates.isEmpty()) {
                        throw new MusicXmlException(path.getFileName() + ": no score document in .mxl");
                    }
                    rootName = candidates.get(0);
                }
                return read(z, rootName);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] read(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name);
        if (e == null) {
            throw new IOException("There is no item named '" + name + "' in the archive");
        }
        try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    public static Score load(Path path) {
        return load(path, "musicxml");
    }

    public static Score load(Path path, String source) {
        return parse(readBytes(path), source);
    }

    // ---- ElementTree-style accessors (the Python helpers _text and _int) ----

    private static String text(Element el, String path, String dflt) {
        if (el == null) {
            return dflt;
        }
        Element found = Xml.find(el, path);
        if (found == null) {
            return dflt;
        }
        String t = Xml.text(found);
        return t == null ? dflt : Py.strip(t);
    }

    private static String text(Element el, String path) {
        return text(el, path, null);
    }

    private static int intOf(Element el, String path, int dflt) {
        String t = text(el, path);
        if (t == null) {
            return dflt;
        }
        try {
            return Py.truncate(Py.parseFloat(t));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static final Set<String> CLEF_SIGNS = Set.of("G", "F", "C", "percussion", "TAB", "none");
    private static final Set<String> SYLLABIC = Set.of("single", "begin", "middle", "end");
    private static final Set<String> ENDING_TYPES = Set.of("start", "stop", "discontinue");
    private static final Set<String> STEPS = Set.of("C", "D", "E", "F", "G", "A", "B");
    private static final Pattern NUMBERS = Pattern.compile("\\d+", Pattern.UNICODE_CHARACTER_CLASS);

    private static String oneOf(String v, Set<String> allowed, String dflt) {
        return v != null && allowed.contains(v) ? v : dflt;
    }

    /** Per-part reading state. */
    private static final class PartState {
        int divisions = 1;
        int staves = 1;
        TimeSignature time;
        /** staff -> measures, in staff order. */
        final TreeMap<Integer, List<Measure>> measures = new TreeMap<>();
    }

    public static Score parse(byte[] data, String source) {
        Element root;
        try {
            root = Xml.parse(data).getDocumentElement();
        } catch (IOException e) {
            throw new MusicXmlException(e.getMessage());
        }
        String rootTag = Xml.tag(root);
        if (rootTag.equals("score-timewise")) {
            throw new MusicXmlException("score-timewise documents are not supported");
        }
        if (!rootTag.equals("score-partwise")) {
            throw new MusicXmlException("not a MusicXML score (root element <" + rootTag + ">)");
        }

        Score score = new Score();
        String workTitle = text(root, "work/work-title");
        score.title = workTitle != null && !workTitle.isEmpty() ? workTitle : text(root, "movement-title");
        for (Element c : Xml.findAll(root, "identification/creator")) {
            String raw = Xml.text(c);
            if ("composer".equals(Xml.attr(c, "type")) && raw != null && !raw.isEmpty()) {
                score.composer = Py.strip(raw);
                break;
            }
        }
        score.source = source;
        score.provenance.add(new Provenance(source));

        Map<String, String[]> partInfo = new HashMap<>();
        for (Element sp : Xml.findAll(root, "part-list/score-part")) {
            String pid = Xml.attr(sp, "id", "");
            partInfo.put(pid, new String[] {text(sp, "part-name"), text(sp, "part-abbreviation")});
        }

        for (Element partEl : Xml.findAll(root, "part")) {
            String pid = Xml.attr(partEl, "id", "P" + (score.parts.size() + 1));
            String[] info = partInfo.getOrDefault(pid, new String[] {null, null});
            score.parts.add(parsePart(partEl, pid, info[0], info[1], source));
        }
        return score;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static Part parsePart(Element partEl, String pid, String name, String abbr, String source) {
        Part part = new Part(pid, emptyToNull(name), emptyToNull(abbr));
        PartState st = new PartState();
        List<Element> elements = Xml.findAll(partEl, "measure");
        int index = 0;
        int shift = 0; // measures added so far that the file's own numbers leave out
        for (int k = 0; k < elements.size(); k++) {
            Element mEl = elements.get(k);
            String own = Xml.attr(mEl, "number", "");
            if (shift != 0 && Py.isDigit(own)) {
                mEl.setAttribute("number", Integer.toString(Py.parseInt(own) + shift));
            }
            parseMeasure(mEl, index, st, part);
            index += 1;
            int rests = intOf(mEl, "attributes/measure-style/multiple-rest", 1);
            List<Element> following = elements.subList(Math.min(k + 1, elements.size()),
                    Math.min(Math.max(k + rests, k + 1), elements.size()));
            boolean writtenOut = following.size() == rests - 1;
            if (writtenOut) {
                for (Element f : following) {
                    if (!restsOnly(f)) {
                        writtenOut = false;
                        break;
                    }
                }
            }
            if (rests > 1 && !writtenOut) {
                // A multi-measure rest written as one measure (as Audiveris exports it): the
                // measures it stands for, each a whole-measure rest.
                String first = Xml.attr(mEl, "number", "");
                for (int j = 1; j < rests; j++) {
                    restMeasure(st, index, Py.isDigit(first) ? Integer.toString(Py.parseInt(first) + j) : "",
                            source);
                    index += 1;
                }
                String after = k + 1 < elements.size() ? Xml.attr(elements.get(k + 1), "number", "") : "";
                if (Py.isDigit(own) && Py.isDigit(after) && Py.parseInt(after) == Py.parseInt(own) + 1) {
                    shift += rests - 1;
                }
            }
        }

        for (Map.Entry<Integer, List<Measure>> e : st.measures.entrySet()) {
            part.staves.add(new Staff(e.getKey(), e.getValue()));
        }
        Comparator<Event> byOnset = Comparator.<Event, Frac>comparing(e -> e.offset)
                .thenComparing(e -> !e.grace);
        for (Staff staff : part.staves) {
            for (Measure measure : staff.measures) {
                for (Voice voice : measure.voices) {
                    voice.events.sort(byOnset); // stable, like list.sort
                }
                measure.voices.sort(Comparator.comparingInt(v -> v.number));
            }
        }
        return part;
    }

    /** A measure whose notes are all rests (or that has none). */
    private static boolean restsOnly(Element mEl) {
        for (Element n : Xml.findAll(mEl, "note")) {
            if (Xml.find(n, "rest") == null) {
                return false;
            }
        }
        return true;
    }

    /** A whole-measure rest in every staff at {@code index}, as long as the time signature. */
    private static void restMeasure(PartState st, int index, String number, String source) {
        Frac length = st.time != null ? st.time.measureLength() : Frac.of(4);
        for (int staff = 1; staff <= st.staves; staff++) {
            Event rest = new Event(Event.REST, Frac.ZERO, length);
            rest.measureRest = true;
            rest.provenance.add(new Provenance(source, MULTI_REST));
            measureFor(st, staff, index, number, false).voice(1).events.add(rest);
        }
    }

    private static Measure measureFor(PartState st, int staff, int index, String number, boolean implicit) {
        List<Measure> measures = st.measures.computeIfAbsent(staff, s -> new ArrayList<>());
        while (measures.size() <= index) {
            // Fill gaps so every staff has an entry for every measure index.
            int i = measures.size();
            measures.add(new Measure(i, i == index ? number : "", implicit));
        }
        return measures.get(index);
    }

    private static void parseMeasure(Element mEl, int index, PartState st, Part part) {
        String number = Xml.attr(mEl, "number", Integer.toString(index + 1));
        boolean implicit = "yes".equals(Xml.attr(mEl, "implicit"));

        // Make sure every known staff has this measure, even if it has no notes.
        for (int staff = 1; staff <= st.staves; staff++) {
            measureFor(st, staff, index, number, implicit);
        }

        int cursor = 0; // in divisions
        int lastOnset = 0;
        Map<List<Integer>, Event> lastEvent = new HashMap<>();

        for (Element el : Xml.children(mEl)) {
            switch (Xml.tag(el)) {
                case "attributes" -> applyAttributes(el, st, part, index, number, implicit,
                        Frac.of(cursor, st.divisions));
                case "backup" -> cursor = Math.max(cursor - intOf(el, "duration", 0), 0);
                case "forward" -> cursor += intOf(el, "duration", 0);
                case "note" -> {
                    int staff = intOf(el, "staff", 1);
                    int voiceNo = intOf(el, "voice", 1);
                    Measure measure = measureFor(st, staff, index, number, implicit);
                    boolean isChord = Xml.find(el, "chord") != null;
                    boolean isGrace = Xml.find(el, "grace") != null;
                    int divs = isGrace ? 0 : intOf(el, "duration", 0);
                    int onset = isChord ? lastOnset : cursor;
                    NoteHead head = noteHead(el);
                    List<Integer> key = List.of(staff, voiceNo);
                    Event prev = lastEvent.get(key);
                    if (isChord && head != null && prev != null && !Event.REST.equals(prev.kind)) {
                        prev.notes.add(head);
                        prev.kind = Event.CHORD;
                        for (Slur sl : slurs(el)) if (!prev.slurs.contains(sl)) prev.slurs.add(sl);
                        continue;
                    }
                    Event event = event(el, Frac.of(onset, st.divisions), Frac.of(divs, st.divisions),
                            head, isGrace);
                    measure.voice(voiceNo).events.add(event);
                    lastEvent.put(key, event);
                    if (!isChord && !isGrace) {
                        lastOnset = cursor;
                        cursor += divs;
                    } else if (!isChord) {
                        lastOnset = cursor;
                    }
                }
                case "harmony" -> {
                    Measure measure = measureFor(st, intOf(el, "staff", 1), index, number, implicit);
                    ChordSymbol chord = chordSymbol(el,
                            Frac.of(cursor + intOf(el, "offset", 0), st.divisions));
                    if (chord != null) {
                        measure.chordSymbols.add(chord);
                    }
                }
                case "barline" -> {
                    Barline barline = barline(el); // one object, shared by every staff
                    boolean left = "left".equals(Xml.attr(el, "location", "right"));
                    for (int staff = 1; staff <= st.staves; staff++) {
                        Measure measure = measureFor(st, staff, index, number, implicit);
                        if (left) {
                            measure.leftBarline = barline;
                        } else {
                            measure.rightBarline = barline;
                        }
                    }
                }
                case "direction" -> {
                    List<Direction> found = directions(el, Frac.of(cursor + intOf(el, "offset", 0), st.divisions));
                    if (!found.isEmpty()) {
                        measureFor(st, intOf(el, "staff", 1), index, number, implicit).directions.addAll(found);
                    }
                }
                default -> {
                    // other elements (print, sound, ...) are not part of the IR
                }
            }
        }
    }

    private static List<Integer> targets(Element el, PartState st) {
        String n = Xml.attr(el, "number");
        if (n != null && !n.isEmpty()) {
            return List.of(Py.parseInt(n));
        }
        List<Integer> all = new ArrayList<>();
        for (int s = 1; s <= st.staves; s++) {
            all.add(s);
        }
        return all;
    }

    private static void applyAttributes(Element el, PartState st, Part part, int index, String number,
            boolean implicit, Frac offset) {
        int divisions = intOf(el, "divisions", 0);
        if (divisions > 0) {
            st.divisions = divisions;
        }
        int staves = intOf(el, "staves", 0);
        if (staves > 0) {
            st.staves = staves;
            for (int staff = 1; staff <= staves; staff++) {
                measureFor(st, staff, index, number, implicit);
            }
        }

        for (Element keyEl : Xml.findAll(el, "key")) {
            int fifths = intOf(keyEl, "fifths", 0);
            String mode = text(keyEl, "mode");
            KeySignature key = new KeySignature(Math.max(-7, Math.min(7, fifths)),
                    "major".equals(mode) || "minor".equals(mode) ? mode : null);
            for (int staff : targets(keyEl, st)) {
                measureFor(st, staff, index, number, implicit).key = key;
            }
        }

        for (Element timeEl : Xml.findAll(el, "time")) {
            String beatsText = text(timeEl, "beats", "4");
            if (beatsText == null || beatsText.isEmpty()) {
                beatsText = "4";
            }
            int beats = 0;
            Matcher m = NUMBERS.matcher(beatsText);
            while (m.find()) {
                beats += Py.parseInt(m.group());
            }
            if (beats == 0) {
                beats = 4;
            }
            int beatType = intOf(timeEl, "beat-type", 4);
            String symbol = Xml.attr(timeEl, "symbol");
            TimeSignature ts = new TimeSignature(beats, beatType,
                    "common".equals(symbol) || "cut".equals(symbol) ? symbol : "normal");
            st.time = ts;
            for (int staff : targets(timeEl, st)) {
                measureFor(st, staff, index, number, implicit).time = ts;
            }
        }

        for (Element clefEl : Xml.findAll(el, "clef")) {
            int line = intOf(clefEl, "line", 0);
            Clef clef = new Clef(oneOf(text(clefEl, "sign"), CLEF_SIGNS, "none"), line == 0 ? null : line,
                    intOf(clefEl, "clef-octave-change", 0), offset);
            int staff = Py.parseInt(Xml.attr(clefEl, "number", "1"));
            measureFor(st, staff, index, number, implicit).clefs.add(clef);
        }

        String chromatic = text(el, "transpose/chromatic");
        if (chromatic != null) {
            int octave = intOf(el, "transpose/octave-change", 0);
            part.transposeSemitones = Py.truncate(Py.parseFloat(chromatic)) + 12 * octave;
        }
    }

    private static NoteHead noteHead(Element el) {
        String step;
        int alter;
        int octave;
        Element pitchEl = Xml.find(el, "pitch");
        if (pitchEl != null) {
            step = text(pitchEl, "step", "C");
            if (step == null || step.isEmpty()) {
                step = "C";
            }
            String alterText = text(pitchEl, "alter");
            alter = alterText != null && !alterText.isEmpty() ? Py.round(Py.parseFloat(alterText)) : 0;
            octave = intOf(pitchEl, "octave", 4);
        } else {
            Element unpitched = Xml.find(el, "unpitched");
            if (unpitched == null) {
                return null;
            }
            step = text(unpitched, "display-step", "C");
            if (step == null || step.isEmpty()) {
                step = "C";
            }
            alter = 0;
            octave = intOf(unpitched, "display-octave", 4);
        }
        if (!STEPS.contains(step)) {
            return null;
        }
        Set<String> ties = new HashSet<>();
        for (Element t : Xml.findAll(el, "tie")) {
            ties.add(Xml.attr(t, "type"));
        }
        for (Element t : Xml.findAll(el, "notations/tied")) {
            ties.add(Xml.attr(t, "type"));
        }
        return new NoteHead(new Pitch(step, Math.max(-2, Math.min(2, alter)), octave),
                ties.contains("start"), ties.contains("stop"), Xml.find(el, "accidental") != null);
    }

    private static Event event(Element el, Frac offset, Frac duration, NoteHead head, boolean grace) {
        Element restEl = Xml.find(el, "rest");
        Tuplet tuplet = null;
        Element tm = Xml.find(el, "time-modification");
        if (tm != null) {
            tuplet = new Tuplet(intOf(tm, "actual-notes", 1), intOf(tm, "normal-notes", 1));
        }
        List<Lyric> lyrics = new ArrayList<>();
        List<Element> lyricEls = Xml.findAll(el, "lyric");
        for (int i = 0; i < lyricEls.size(); i++) {
            Element ly = lyricEls.get(i);
            StringBuilder sb = new StringBuilder();
            for (Element t : Xml.findAll(ly, "text")) {
                String s = Xml.text(t);
                sb.append(s == null ? "" : s);
            }
            String txt = Py.strip(sb.toString());
            if (txt.isEmpty()) {
                continue;
            }
            String verseAttr = Xml.attr(ly, "number", "");
            int verse = Py.isDigit(verseAttr) ? Py.parseInt(verseAttr) : i + 1;
            lyrics.add(new Lyric(txt, verse, oneOf(text(ly, "syllabic"), SYLLABIC, "single"),
                    Xml.find(ly, "extend") != null));
        }
        List<String> articulations = new ArrayList<>();
        for (Element a : Xml.findAll(el, "notations/articulations/*")) {
            articulations.add(Xml.tag(a));
        }
        for (Element t : Xml.findAll(el, "notations/technical/*")) {
            if (Event.BOWINGS.contains(Xml.tag(t))) {
                articulations.add(Xml.tag(t));
            }
        }
        for (Element o : Xml.findAll(el, "notations/ornaments/*")) {
            if (Event.ORNAMENTS.contains(Xml.tag(o))) {
                articulations.add(Xml.tag(o));
            }
        }
        if (Xml.find(el, "notations/fermata") != null) {
            articulations.add("fermata");
        }
        Event e = new Event(restEl != null || head == null ? Event.REST : Event.NOTE, offset, duration);
        if (head != null && restEl == null) {
            e.notes.add(head);
        }
        e.noteType = text(el, "type");
        e.dots = Xml.findAll(el, "dot").size();
        e.tuplet = tuplet;
        e.grace = grace;
        e.measureRest = restEl != null && "yes".equals(Xml.attr(restEl, "measure"));
        e.lyrics = lyrics;
        e.articulations = articulations;
        e.slurs = slurs(el);
        return e;
    }

    private static String alterName(String step, int alter) {
        return step + (alter > 0 ? "#".repeat(alter) : "b".repeat(-alter));
    }

    /** {@code round(float(_text(el, path, "0") or 0))}. */
    private static int alterOf(Element el, String path) {
        String t = text(el, path, "0");
        return t == null || t.isEmpty() ? 0 : Py.round(Py.parseFloat(t));
    }

    /** The slur ends on a note ({@code continue} carries no new information). */
    private static List<Slur> slurs(Element el) {
        List<Slur> out = new ArrayList<>();
        for (Element sl : Xml.findAll(el, "notations/slur")) {
            String type = Xml.attr(sl, "type");
            if ("start".equals(type) || "stop".equals(type)) {
                Slur slur = new Slur(type, intAttr(sl, "number", 1));
                if (!out.contains(slur)) out.add(slur);
            }
        }
        return out;
    }

    /** Three or more dots or dashes in a row (spaces between allowed), not starting right after a
     *  letter, so an abbreviation keeps its dot ("cresc. - - -" becomes "cresc."). */
    private static final java.util.regex.Pattern CONTINUATION =
            java.util.regex.Pattern.compile("(?<!\\p{L})(?:\\s*[._\\-\u2013\u2014\u00b7\u2026]){3,}\\s*");

    /** MusicXML dynamics this importer knows by name (the rest are kept as their text). */
    private static final java.util.Set<String> DYNAMICS = java.util.Set.of("p", "pp", "ppp", "pppp", "ppppp",
            "pppppp", "f", "ff", "fff", "ffff", "fffff", "ffffff", "mp", "mf", "sf", "sfp", "sfpp", "fp", "rf",
            "rfz", "sfz", "sffz", "fz", "n", "pf", "sfzp");

    /** The marks in one {@code <direction>} that the IR keeps. */
    private static List<Direction> directions(Element el, Frac offset) {
        List<Direction> out = new ArrayList<>();
        String placement = Xml.attr(el, "placement");
        for (Element shift : Xml.findAll(el, "direction-type/octave-shift")) {
            String type = Xml.attr(shift, "type");
            if (!"up".equals(type) && !"down".equals(type) && !"stop".equals(type)) {
                continue; // "continue" carries no new information
            }
            Direction d = new Direction(Direction.OCTAVE_SHIFT, offset);
            d.type = type;
            d.number = intAttr(shift, "number", 1);
            d.size = "stop".equals(type) ? null : intAttr(shift, "size", 8);
            d.placement = placement;
            out.add(d);
        }
        for (Element dyn : Xml.findAll(el, "direction-type/dynamics/*")) {
            String name = Xml.tag(dyn);
            String text = DYNAMICS.contains(name) ? name : "other-dynamics".equals(name) ? Xml.text(dyn) : null;
            if (text == null || text.isBlank()) continue;
            Direction d = new Direction(Direction.DYNAMICS, offset);
            d.text = text;
            d.placement = placement;
            out.add(d);
        }
        for (Element wedge : Xml.findAll(el, "direction-type/wedge")) {
            String type = Xml.attr(wedge, "type");
            if (!"crescendo".equals(type) && !"diminuendo".equals(type) && !"stop".equals(type)) continue;
            Direction d = new Direction(Direction.WEDGE, offset);
            d.type = type;
            d.number = intAttr(wedge, "number", 1);
            d.placement = placement;
            out.add(d);
        }
        for (Element words : Xml.findAll(el, "direction-type/words")) {
            String t = Xml.text(words);
            // A continuation line (an 8va's, a "cresc. - - -") read as dots or dashes is dropped.
            t = t == null ? "" : CONTINUATION.matcher(t).replaceAll(" ").strip().replaceAll("\\s+", " ");
            if (t.codePoints().noneMatch(Character::isLetterOrDigit)) continue;
            Direction d = new Direction(Direction.WORDS, offset);
            d.text = t;
            d.placement = placement;
            out.add(d);
        }
        for (Element metronome : Xml.findAll(el, "direction-type/metronome")) {
            String unit = text(metronome, "beat-unit");
            String perMinute = text(metronome, "per-minute");
            if (unit == null || perMinute == null) continue;
            Direction d = new Direction(Direction.METRONOME, offset);
            d.type = unit + ".".repeat(Xml.findAll(metronome, "beat-unit-dot").size());
            d.text = perMinute;
            d.placement = placement;
            out.add(d);
        }
        return out;
    }

    private static int intAttr(Element el, String name, int dflt) {
        String t = Xml.attr(el, name);
        if (t == null || t.isBlank()) {
            return dflt;
        }
        try {
            return Py.truncate(Py.parseFloat(t));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static ChordSymbol chordSymbol(Element el, Frac offset) {
        String rootStep = text(el, "root/root-step");
        if (rootStep == null) {
            return null;
        }
        String root = alterName(rootStep, alterOf(el, "root/root-alter"));
        Element kindEl = Xml.find(el, "kind");
        String kind;
        if (kindEl != null) {
            String raw = Xml.text(kindEl);
            kind = Py.strip(raw == null ? "" : raw);
        } else {
            kind = "major";
        }
        if (kind.isEmpty()) {
            kind = "none"; // _KIND_ALIASES
        }
        String bassStep = text(el, "bass/bass-step");
        String bass = bassStep != null && !bassStep.isEmpty()
                ? alterName(bassStep, alterOf(el, "bass/bass-alter")) : null;
        String label = kindEl != null ? Xml.attr(kindEl, "text") : null;
        return new ChordSymbol(offset, root, kind, bass, label);
    }

    private static Barline barline(Element el) {
        Element repeatEl = Xml.find(el, "repeat");
        Element endingEl = Xml.find(el, "ending");
        String style = text(el, "bar-style", "regular");
        String direction = repeatEl != null ? Xml.attr(repeatEl, "direction") : null;
        String endingType = endingEl != null ? Xml.attr(endingEl, "type") : null;
        return new Barline(style == null || style.isEmpty() ? "regular" : style,
                "forward".equals(direction) || "backward".equals(direction) ? direction : null,
                endingEl != null ? Xml.attr(endingEl, "number") : null,
                oneOf(endingType, ENDING_TYPES, null));
    }
}

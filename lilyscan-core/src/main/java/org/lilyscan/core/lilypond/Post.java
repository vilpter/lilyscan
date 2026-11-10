package org.lilyscan.core.lilypond;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lilyscan.core.ir.Direction;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Slur;
import org.lilyscan.core.ir.Voice;

/**
 * The post-events a staff's notes carry beyond their own articulations: slurs, dynamics,
 * hairpins and words.
 *
 * <p>LilyPond warns about a slur or hairpin it cannot close, so only complete ones are written:
 * a slur's two ends must be in the same LilyPond voice (the staff's main voice, which runs
 * through every measure, or one measure's extra {@code \new Voice}), and a hairpin is ended by
 * its stop or the next dynamic. Ends that don't pair are left out.
 */
final class Post {
    /** LilyPond's own dynamic marks; others are made with {@code make-dynamic-script}. */
    private static final Set<String> LILY_DYNAMICS = Set.of("ppppp", "pppp", "ppp", "pp", "p", "mp", "mf",
            "f", "ff", "fff", "ffff", "fffff", "fp", "sf", "sff", "sp", "spp", "sfz", "rfz", "n");

    private final Map<Event, StringBuilder> events = new IdentityHashMap<>();
    private final Map<Measure, StringBuilder> rests = new IdentityHashMap<>();

    private Post() {}

    String of(Event e) {
        StringBuilder sb = events.get(e);
        return sb == null ? "" : sb.toString();
    }

    /** For a measure engraved as a whole-measure rest (no voices). */
    String of(Measure m) {
        StringBuilder sb = rests.get(m);
        return sb == null ? "" : sb.toString();
    }

    private void add(Event e, String mark) {
        events.computeIfAbsent(e, k -> new StringBuilder()).append(mark);
    }

    private record Target(Event event, Measure rest) {}

    private void add(Target t, String mark) {
        if (t.event() != null) {
            add(t.event(), mark);
        } else {
            rests.computeIfAbsent(t.rest(), k -> new StringBuilder()).append(mark);
        }
    }

    static Post of(List<Measure> measures) {
        Post post = new Post();
        post.slurs(measures);
        post.directions(measures);
        return post;
    }

    // ---- slurs ----

    private void slurs(List<Measure> measures) {
        List<Event> main = new ArrayList<>();
        for (Measure m : measures) {
            List<Voice> voices = Generate.engravedVoices(m);
            for (int k = 0; k < voices.size(); k++) {
                if (k == 0) {
                    main.addAll(voices.get(0).events);
                } else {
                    pairSlurs(voices.get(k).events);
                }
            }
        }
        pairSlurs(main);
    }

    /** Keeps the slurs whose start and stop are both in {@code chain}, one LilyPond voice. */
    private void pairSlurs(List<Event> chain) {
        Map<Integer, Event> open = new java.util.HashMap<>();
        Map<Event, List<String>> stops = new IdentityHashMap<>();
        Map<Event, List<String>> starts = new IdentityHashMap<>();
        for (Event e : chain) {
            for (Slur s : e.slurs) {
                if (s.isStop()) {
                    Event start = open.remove(s.number);
                    if (start != null && start != e) {
                        starts.computeIfAbsent(start, k -> new ArrayList<>()).add(open(s.number));
                        stops.computeIfAbsent(e, k -> new ArrayList<>()).add(close(s.number));
                    }
                }
            }
            for (Slur s : e.slurs) {
                if (s.isStart()) open.put(s.number, e);
            }
        }
        for (Event e : chain) {
            for (String m : stops.getOrDefault(e, List.of())) add(e, m);
            for (String m : starts.getOrDefault(e, List.of())) add(e, m);
        }
    }

    private static String open(int number) {
        return number == 1 ? "(" : "\\=" + number + "(";
    }

    private static String close(int number) {
        return number == 1 ? ")" : "\\=" + number + ")";
    }

    // ---- directions ----

    /** The note (or whole-measure rest) a direction at {@code d.offset} is written on: the first
     *  note of the main voice at or after it, else the measure's last. */
    private static Target target(Measure m, Direction d) {
        List<Voice> voices = Generate.engravedVoices(m);
        if (voices.isEmpty()) return new Target(null, m);
        Event last = null;
        for (Event e : voices.get(0).events) {
            if (e.grace) continue;
            if (!e.offset.lt(d.offset)) return new Target(e, null);
            last = e;
        }
        return last != null ? new Target(last, null) : new Target(voices.get(0).events.get(0), null);
    }

    private void directions(List<Measure> measures) {
        boolean hairpin = false;
        Target lastStart = null;
        for (Measure m : measures) {
            List<Direction> ds = new ArrayList<>(m.directions);
            ds.sort((a, b) -> a.offset.compareTo(b.offset));
            List<Direction> tempoWords = new ArrayList<>();
            for (Direction d : ds) {
                if (Direction.METRONOME.equals(d.kind)) {
                    Direction w = Generate.tempoWords(m, d);
                    if (w != null && Generate.tempoCommand(d, w) != null) tempoWords.add(w);
                }
            }
            for (Direction d : ds) {
                switch (d.kind) {
                    case Direction.DYNAMICS -> {
                        add(target(m, d), dynamic(d.text));
                        hairpin = false;
                    }
                    case Direction.WEDGE -> {
                        Target t = target(m, d);
                        if ("stop".equals(d.type)) {
                            if (hairpin && !t.equals(lastStart)) add(t, "\\!");
                            hairpin = false;
                        } else {
                            add(t, "crescendo".equals(d.type) ? "\\<" : "\\>");
                            hairpin = true;
                            lastStart = t;
                        }
                    }
                    case Direction.WORDS -> {
                        if (tempoWords.stream().noneMatch(w -> w == d)) {
                            add(target(m, d), side(d.placement) + Notation.lilyString(d.text));
                        }
                    }
                    default -> {
                        // octave shifts and metronome marks are staff commands (Generate's cues)
                    }
                }
            }
        }
        if (hairpin) {
            // A hairpin without its end: end it on the staff's last note.
            for (int i = measures.size() - 1; i >= 0; i--) {
                List<Voice> voices = Generate.engravedVoices(measures.get(i));
                if (voices.isEmpty()) continue;
                List<Event> events = voices.get(0).events;
                Target t = new Target(events.get(events.size() - 1), null);
                if (!t.equals(lastStart)) add(t, "\\!");
                break;
            }
        }
    }

    private static String side(String placement) {
        return "above".equals(placement) ? "^" : "below".equals(placement) ? "_" : "-";
    }

    private static String dynamic(String text) {
        return LILY_DYNAMICS.contains(text) ? "\\" + text : "-#(make-dynamic-script " + Notation.lilyString(text) + ")";
    }
}

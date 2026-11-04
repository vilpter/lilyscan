package org.lilyscan.eval.synth;

import java.util.ArrayList;
import java.util.List;
import org.lilyscan.core.ir.Clef;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.KeySignature;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.TimeSignature;

/**
 * Measures {@code first .. first + count - 1} of a real work, by printed measure number, as
 * music21's {@code Score.measures} takes them: the clef, key and meter in force at the excerpt's
 * start are restated in its first measure.
 */
public final class Excerpt {
    private Excerpt() {}

    /** The measure's number as an integer ({@code 12a} is 12; a pickup is 0), or -1 when it has none. */
    static int number(Measure m) {
        String digits = m.number == null ? "" : m.number.replaceAll("[^0-9].*$", "");
        return digits.isEmpty() ? -1 : Integer.parseInt(digits);
    }

    public static Score of(Score full, int first, int count) {
        int last = first + count - 1;
        Score out = new Score();
        out.title = full.title;
        out.composer = full.composer;
        out.source = full.source;
        for (Part p : full.parts) {
            Part q = new Part(p.id, p.name, p.abbreviation);
            q.transposeSemitones = p.transposeSemitones;
            for (Staff s : p.staves) {
                Clef clef = null;
                KeySignature key = null;
                TimeSignature time = null;
                List<Measure> kept = new ArrayList<>();
                for (Measure m : s.measures) {
                    int n = number(m);
                    if (n >= first && n <= last) {
                        kept.add(m);
                        continue;
                    }
                    if (n >= 0 && n > last) break;
                    if (kept.isEmpty()) {
                        if (!m.clefs.isEmpty()) clef = m.clefs.get(m.clefs.size() - 1);
                        if (m.key != null) key = m.key;
                        if (m.time != null) time = m.time;
                    }
                }
                if (!kept.isEmpty()) {
                    Measure head = kept.get(0);
                    boolean clefAtStart = head.clefs.stream().anyMatch(c -> c.offset == null || c.offset.isZero());
                    if (!clefAtStart && clef != null) {
                        head.clefs.add(0, new Clef(clef.sign, clef.line, clef.octaveChange, Frac.ZERO));
                    }
                    if (head.key == null) head.key = key;
                    if (head.time == null) head.time = time;
                    for (int i = 0; i < kept.size(); i++) kept.get(i).index = i;
                }
                q.staves.add(new Staff(s.number, kept));
            }
            out.parts.add(q);
        }
        return out;
    }
}

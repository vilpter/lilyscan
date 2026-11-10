package org.lilyscan.core.repair;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.omr.OmrBook;

/**
 * Stage 5 (port of {@code lilyscan/repair/__init__.py}): the repair rules, in order. Each edits
 * the score in place, records provenance on every event it changes, and returns log entries.
 */
public final class Repairs {
    private Repairs() {}

    /** Parts are merged first, so later rules see each part's whole line (an octave shift's ends
     *  are then on one staff); lyrics are cleaned last. */
    public static final List<Function<Score, List<Repair>>> DEFAULT = List.of(
            Parts::mergeSplitParts, Ottava::missingEnds, Clefs::octaveClefs, Rhythm::repairRhythm, Lyrics::cleanLyrics);

    /** The default rules, then (with the engine's {@code .omr}) the rules that need page geometry. */
    public static List<Repair> apply(Score score, OmrBook book) {
        return apply(score, null, book);
    }

    public static List<Repair> apply(Score score, List<Function<Score, List<Repair>>> rules, OmrBook book) {
        List<Repair> log = new ArrayList<>();
        for (Function<Score, List<Repair>> rule : rules != null ? rules : DEFAULT) log.addAll(rule.apply(score));
        if (book != null && rules == null) {
            log.addAll(Keys.consistentKeys(score, book));
            log.addAll(Syllables.splitGluedSyllables(score, book));
        }
        return log;
    }
}

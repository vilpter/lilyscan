package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Map;
import java.util.Objects;

/** A written pitch in scientific notation: middle C is C4. */
@JsonPropertyOrder({"step", "alter", "octave"})
public final class Pitch {
    public static final String STEPS = "CDEFGAB";
    private static final Map<String, Integer> SEMITONES =
            Map.of("C", 0, "D", 2, "E", 4, "F", 5, "G", 7, "A", 9, "B", 11);

    /** One of C D E F G A B. */
    public String step;
    /** -2..2. */
    public int alter;
    public int octave;

    public Pitch() {}

    public Pitch(String step, int alter, int octave) {
        this.step = step;
        this.alter = alter;
        this.octave = octave;
    }

    public int midi() {
        return 12 * (octave + 1) + SEMITONES.get(step) + alter;
    }

    /** Diatonic step number (C4 = 28); independent of alteration. */
    public int diatonic() {
        return 7 * octave + STEPS.indexOf(step);
    }

    /** {@code (step, alter, octave)}, for use as a map key. */
    public Key key() {
        return new Key(step, alter, octave);
    }

    /** Value key of a pitch. */
    public record Key(String step, int alter, int octave) {}

    /** Human-readable name, e.g. {@code F#4} or {@code Eb2}. */
    public String label() {
        String acc = alter > 0 ? "#".repeat(alter) : "b".repeat(-alter);
        return step + acc + octave;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Pitch p && Objects.equals(p.step, step) && p.alter == alter
                && p.octave == octave;
    }

    @Override
    public int hashCode() {
        return Objects.hash(step, alter, octave);
    }

    @Override
    public String toString() {
        return label();
    }
}

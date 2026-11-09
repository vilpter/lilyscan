package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/**
 * A MusicXML {@code <direction>} mark on one staff, at an offset within its measure: an octave
 * shift, a dynamic, a hairpin, words or a metronome mark.
 *
 * <p>Only the fields a kind uses are set; the others stay null and are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"kind", "offset", "type", "number", "size", "placement", "text"})
public final class Direction {
    public static final String OCTAVE_SHIFT = "octave-shift";

    /** {@code octave-shift}, {@code dynamics}, {@code wedge}, {@code words} or {@code metronome}. */
    public String kind;
    public Frac offset;
    /**
     * Octave shift: {@code down} (8va: printed lower than it sounds), {@code up} (8vb) or
     * {@code stop}.
     */
    public String type;
    /** Pairs a start with its stop when spans overlap (MusicXML {@code number}). */
    public Integer number;
    /** Octave shift: 8, 15 or 22. */
    public Integer size;
    /** {@code above} or {@code below} the staff, when stated. */
    public String placement;
    public String text;

    public Direction() {}

    public Direction(String kind, Frac offset) {
        this.kind = kind;
        this.offset = offset;
    }

    /** An octave shift's distance in octaves (8 → 1, 15 → 2, 22 → 3), signed to move a printed
     *  pitch to the sounding one; 0 for a stop or another kind. */
    public int octaves() {
        if (!OCTAVE_SHIFT.equals(kind) || size == null) {
            return 0;
        }
        int n = Math.max(1, (size - 1) / 7);
        return "down".equals(type) ? n : "up".equals(type) ? -n : 0;
    }

    public boolean isOctaveStart() {
        return OCTAVE_SHIFT.equals(kind) && ("down".equals(type) || "up".equals(type));
    }

    public boolean isOctaveStop() {
        return OCTAVE_SHIFT.equals(kind) && "stop".equals(type);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Direction d && Objects.equals(d.kind, kind) && Objects.equals(d.offset, offset)
                && Objects.equals(d.type, type) && Objects.equals(d.number, number)
                && Objects.equals(d.size, size) && Objects.equals(d.placement, placement)
                && Objects.equals(d.text, text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, offset, type, number, size, placement, text);
    }

    @Override
    public String toString() {
        return "Direction(" + kind + " " + type + " @" + offset + ")";
    }
}

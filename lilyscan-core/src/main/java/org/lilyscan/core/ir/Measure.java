package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One measure of one staff. {@code index} is 0-based and aligned across staves. */
@JsonPropertyOrder({"index", "number", "clefs", "key", "time", "voices", "chordSymbols",
    "leftBarline", "rightBarline", "implicit", "bbox", "confidence", "directions"})
public final class Measure {
    public int index;
    /** As printed, e.g. {@code 12}, {@code 12a}, {@code ""} for pickups. */
    public String number;
    /** Clef changes within this measure. */
    public List<Clef> clefs = new ArrayList<>();
    /** Set when the key is stated or changes here. */
    public KeySignature key;
    /** Set when the time signature changes here. */
    public TimeSignature time;
    public List<Voice> voices = new ArrayList<>();
    public List<ChordSymbol> chordSymbols = new ArrayList<>();
    public Barline leftBarline;
    public Barline rightBarline;
    /** Pickup / incomplete measure not counted in numbering. */
    public boolean implicit;
    public BBox bbox;
    public Double confidence;
    /** Direction marks (octave shifts, ...) on this staff, by offset. Left out of the JSON when
     *  empty, so IR written before they existed reads and writes back unchanged. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<Direction> directions = new ArrayList<>();

    public Measure() {}

    public Measure(int index, String number) {
        this.index = index;
        this.number = number;
    }

    public Measure(int index, String number, boolean implicit) {
        this.index = index;
        this.number = number;
        this.implicit = implicit;
    }

    /** The voice numbered {@code number}, appended if missing. */
    public Voice voice(int number) {
        for (Voice v : voices) {
            if (v.number == number) {
                return v;
            }
        }
        Voice v = new Voice(number);
        voices.add(v);
        return v;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Measure m && m.index == index && Objects.equals(m.number, number)
                && Objects.equals(m.clefs, clefs) && Objects.equals(m.key, key)
                && Objects.equals(m.time, time) && Objects.equals(m.voices, voices)
                && Objects.equals(m.chordSymbols, chordSymbols)
                && Objects.equals(m.leftBarline, leftBarline)
                && Objects.equals(m.rightBarline, rightBarline) && m.implicit == implicit
                && Objects.equals(m.bbox, bbox) && Objects.equals(m.confidence, confidence)
                && Objects.equals(m.directions, directions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, number, clefs, key, time, voices, chordSymbols, leftBarline,
                rightBarline, implicit, bbox, confidence, directions);
    }
}

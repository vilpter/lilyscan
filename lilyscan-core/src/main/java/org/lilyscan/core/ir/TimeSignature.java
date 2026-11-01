package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/** A time signature. */
@JsonPropertyOrder({"beats", "beatType", "symbol"})
public final class TimeSignature {
    public int beats;
    public int beatType;
    /** {@code common}, {@code cut} or {@code normal}. */
    public String symbol = "normal";

    public TimeSignature() {}

    public TimeSignature(int beats, int beatType, String symbol) {
        this.beats = beats;
        this.beatType = beatType;
        this.symbol = symbol;
    }

    /** Length of a full measure in quarter notes. */
    public Frac measureLength() {
        return Frac.of(4L * beats, beatType);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TimeSignature t && t.beats == beats && t.beatType == beatType
                && Objects.equals(t.symbol, symbol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(beats, beatType, symbol);
    }
}

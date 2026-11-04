package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/** A barline, with repeat and volta information. */
@JsonPropertyOrder({"style", "repeat", "ending", "endingType"})
public final class Barline {
    /** {@code regular}, {@code light-light}, {@code light-heavy}, ... */
    public String style = "regular";
    /** {@code forward}, {@code backward} or null. */
    public String repeat;
    /** Volta number(s), e.g. {@code 1} or {@code 1, 2}. */
    public String ending;
    /** {@code start}, {@code stop}, {@code discontinue} or null. */
    public String endingType;

    public Barline() {}

    public Barline(String style, String repeat, String ending, String endingType) {
        this.style = style;
        this.repeat = repeat;
        this.ending = ending;
        this.endingType = endingType;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Barline b && Objects.equals(b.style, style)
                && Objects.equals(b.repeat, repeat) && Objects.equals(b.ending, ending)
                && Objects.equals(b.endingType, endingType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(style, repeat, ending, endingType);
    }
}

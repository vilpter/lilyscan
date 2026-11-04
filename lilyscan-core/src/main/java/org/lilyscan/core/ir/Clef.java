package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/** A clef (or clef change at {@code offset} within its measure). */
@JsonPropertyOrder({"sign", "line", "octaveChange", "offset"})
public final class Clef {
    /** {@code G}, {@code F}, {@code C}, {@code percussion}, {@code TAB} or {@code none}. */
    public String sign;
    public Integer line;
    public int octaveChange;
    public Frac offset = Frac.ZERO;

    public Clef() {}

    public Clef(String sign, Integer line, int octaveChange, Frac offset) {
        this.sign = sign;
        this.line = line;
        this.octaveChange = octaveChange;
        this.offset = offset;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Clef c && Objects.equals(c.sign, sign) && Objects.equals(c.line, line)
                && c.octaveChange == octaveChange && Objects.equals(c.offset, offset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sign, line, octaveChange, offset);
    }
}

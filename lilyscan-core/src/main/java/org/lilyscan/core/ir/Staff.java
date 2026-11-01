package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One staff of a part. */
@JsonPropertyOrder({"number", "measures"})
public final class Staff {
    /** 1-based within the part. */
    public int number;
    public List<Measure> measures = new ArrayList<>();

    public Staff() {}

    public Staff(int number, List<Measure> measures) {
        this.number = number;
        this.measures = measures;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Staff s && s.number == number && Objects.equals(s.measures, measures);
    }

    @Override
    public int hashCode() {
        return Objects.hash(number, measures);
    }
}

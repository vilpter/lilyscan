package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/** One end of a slur on a note: {@code start} or {@code stop}, paired by {@code number}. */
@JsonPropertyOrder({"type", "number"})
public final class Slur {
    public String type;
    public int number = 1;

    public Slur() {}

    public Slur(String type, int number) {
        this.type = type;
        this.number = number;
    }

    public boolean isStart() {
        return "start".equals(type);
    }

    public boolean isStop() {
        return "stop".equals(type);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Slur s && Objects.equals(s.type, type) && s.number == number;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, number);
    }

    @Override
    public String toString() {
        return "Slur(" + type + " " + number + ")";
    }
}

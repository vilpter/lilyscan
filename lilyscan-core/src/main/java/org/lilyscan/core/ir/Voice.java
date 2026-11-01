package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One voice of one measure. */
@JsonPropertyOrder({"number", "events"})
public final class Voice {
    public int number;
    public List<Event> events = new ArrayList<>();

    public Voice() {}

    public Voice(int number) {
        this.number = number;
    }

    /** Sum of the non-grace durations. */
    public Frac duration() {
        Frac total = Frac.ZERO;
        for (Event e : events) {
            if (!e.grace) {
                total = total.add(e.duration);
            }
        }
        return total;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Voice v && v.number == number && Objects.equals(v.events, events);
    }

    @Override
    public int hashCode() {
        return Objects.hash(number, events);
    }
}

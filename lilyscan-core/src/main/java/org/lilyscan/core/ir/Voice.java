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

    /**
     * Where the voice ends: the latest onset plus duration of a non-grace event. A voice need not
     * be contiguous, so this counts its gaps, which {@link #duration()} does not.
     */
    public Frac end() {
        Frac end = Frac.ZERO;
        for (Event e : events) {
            if (!e.grace) {
                Frac x = e.offset.add(e.duration);
                if (x.gt(end)) {
                    end = x;
                }
            }
        }
        return end;
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

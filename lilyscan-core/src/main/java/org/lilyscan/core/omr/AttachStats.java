package org.lilyscan.core.omr;

import java.util.ArrayList;
import java.util.List;

/** What {@link Geometry#attach} located. */
public final class AttachStats {
    public int events;
    /** Events in measures whose staff exists on the page. Engines pad a part that is absent
     *  from a system with invented rests; those have nothing to locate. */
    public int mappable;
    public int located;
    public int pitchMismatch;
    public int measures;
    public int measuresLocated;
    public List<String> unmappedStaves = new ArrayList<>();

    /** Share of mappable events that received a bounding box. */
    public double locatedRate() {
        return mappable != 0 ? (double) located / mappable : 0.0;
    }
}

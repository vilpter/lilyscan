package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A part (instrument or voice), with one or more staves. */
@JsonPropertyOrder({"id", "name", "abbreviation", "transposeSemitones", "staves"})
public final class Part {
    public String id;
    public String name;
    public String abbreviation;
    /** Written-to-sounding transposition in semitones (e.g. -2 for B-flat clarinet), when known.
     *  Pitches in the IR are always as written. */
    public Integer transposeSemitones;
    public List<Staff> staves = new ArrayList<>();

    public Part() {}

    public Part(String id, String name, String abbreviation) {
        this.id = id;
        this.name = name;
        this.abbreviation = abbreviation;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Part p && Objects.equals(p.id, id) && Objects.equals(p.name, name)
                && Objects.equals(p.abbreviation, abbreviation)
                && Objects.equals(p.transposeSemitones, transposeSemitones)
                && Objects.equals(p.staves, staves);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, abbreviation, transposeSemitones, staves);
    }
}

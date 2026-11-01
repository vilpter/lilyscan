package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lilyscan's score model: Score -> Parts -> Staves -> Measures -> Voices -> Events.
 *
 * <p>Port of {@code lilyscan/ir/models.py}. Times are exact {@link Frac}s of a quarter note.
 * Every event can carry its source bounding box, a confidence, and the provenance of whichever
 * stage created or changed it. Serialized by {@link IrJson} byte-compatibly with the Python
 * {@code ir/score.json} (design-java.md D19).
 */
@JsonPropertyOrder({"title", "composer", "parts", "source", "provenance"})
public final class Score {
    public String title;
    public String composer;
    public List<Part> parts = new ArrayList<>();
    /** e.g. {@code audiveris 5.11.0}, {@code musicxml}, {@code synthetic}. */
    public String source;
    public List<Provenance> provenance = new ArrayList<>();

    public Score() {}

    /** Every (part, staff) pair, in score order. */
    public List<PartStaff> staves() {
        List<PartStaff> out = new ArrayList<>();
        for (Part p : parts) {
            for (Staff s : p.staves) {
                out.add(new PartStaff(p, s));
            }
        }
        return out;
    }

    /** A staff with the part it belongs to. */
    public record PartStaff(Part part, Staff staff) {}

    @Override
    public boolean equals(Object o) {
        return o instanceof Score s && Objects.equals(s.title, title)
                && Objects.equals(s.composer, composer) && Objects.equals(s.parts, parts)
                && Objects.equals(s.source, source) && Objects.equals(s.provenance, provenance);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, composer, parts, source, provenance);
    }
}

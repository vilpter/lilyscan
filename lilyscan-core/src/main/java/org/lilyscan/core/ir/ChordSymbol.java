package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/** A chord symbol at an offset within its measure. */
@JsonPropertyOrder({"offset", "root", "kind", "bass", "text"})
public final class ChordSymbol {
    public Frac offset;
    /** e.g. {@code F#}, {@code Bb}. */
    public String root;
    /** MusicXML kind, e.g. {@code major}, {@code minor-seventh}, {@code half-diminished}. */
    public String kind;
    public String bass;
    /** The label as printed, when known. */
    public String text;

    public ChordSymbol() {}

    public ChordSymbol(Frac offset, String root, String kind, String bass, String text) {
        this.offset = offset;
        this.root = root;
        this.kind = kind;
        this.bass = bass;
        this.text = text;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ChordSymbol c && Objects.equals(c.offset, offset)
                && Objects.equals(c.root, root) && Objects.equals(c.kind, kind)
                && Objects.equals(c.bass, bass) && Objects.equals(c.text, text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(offset, root, kind, bass, text);
    }
}

package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/** A key signature: {@code fifths} in -7..7, and the mode when the source states it. */
@JsonPropertyOrder({"fifths", "mode"})
public final class KeySignature {
    public int fifths;
    /** {@code major}, {@code minor}, or null when not stated in the source. */
    public String mode;

    public KeySignature() {}

    public KeySignature(int fifths, String mode) {
        this.fifths = fifths;
        this.mode = mode;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KeySignature k && k.fifths == fifths && Objects.equals(k.mode, mode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fifths, mode);
    }
}

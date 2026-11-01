package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/** One notehead of a note or chord. */
@JsonPropertyOrder({"pitch", "tieStart", "tieStop", "accidentalShown"})
public final class NoteHead {
    public Pitch pitch;
    public boolean tieStart;
    public boolean tieStop;
    /** Whether an accidental is printed (courtesy or required); pitch.alter holds the sounding
     *  alteration either way. */
    public boolean accidentalShown;

    public NoteHead() {}

    public NoteHead(Pitch pitch) {
        this.pitch = pitch;
    }

    public NoteHead(Pitch pitch, boolean tieStart, boolean tieStop, boolean accidentalShown) {
        this.pitch = pitch;
        this.tieStart = tieStart;
        this.tieStop = tieStop;
        this.accidentalShown = accidentalShown;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NoteHead n && Objects.equals(n.pitch, pitch) && n.tieStart == tieStart
                && n.tieStop == tieStop && n.accidentalShown == accidentalShown;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pitch, tieStart, tieStop, accidentalShown);
    }
}

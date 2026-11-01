package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A note, chord, or rest in one voice.
 *
 * <p>{@code offset} is measured from the start of the measure; {@code duration} is the sounding
 * duration (tuplets already applied). Grace notes have duration 0.
 */
@JsonPropertyOrder({"kind", "offset", "duration", "notes", "noteType", "dots", "tuplet", "grace",
    "measureRest", "lyrics", "articulations", "confidence", "bbox", "provenance"})
public final class Event {
    public static final String NOTE = "note";
    public static final String CHORD = "chord";
    public static final String REST = "rest";

    /** Bowings: MusicXML {@code <technical>} marks kept with the articulations. */
    public static final Set<String> BOWINGS = Set.of("up-bow", "down-bow");

    /** {@code note}, {@code chord} or {@code rest}. */
    public String kind;
    public Frac offset;
    public Frac duration;
    public List<NoteHead> notes = new ArrayList<>();
    /** Notated form: {@code quarter}, {@code eighth}, {@code 16th}, {@code whole}, ... */
    public String noteType;
    public int dots;
    public Tuplet tuplet;
    public boolean grace;
    public boolean measureRest;
    public List<Lyric> lyrics = new ArrayList<>();
    public List<String> articulations = new ArrayList<>();
    public Double confidence;
    public BBox bbox;
    public List<Provenance> provenance = new ArrayList<>();

    public Event() {}

    public Event(String kind, Frac offset, Frac duration) {
        this.kind = kind;
        this.offset = offset;
        this.duration = duration;
    }

    /** Pydantic's {@code model_copy()}: a new event sharing this one's lists and objects. */
    public Event shallowCopy() {
        Event e = new Event(kind, offset, duration);
        e.notes = notes;
        e.noteType = noteType;
        e.dots = dots;
        e.tuplet = tuplet;
        e.grace = grace;
        e.measureRest = measureRest;
        e.lyrics = lyrics;
        e.articulations = articulations;
        e.confidence = confidence;
        e.bbox = bbox;
        e.provenance = provenance;
        return e;
    }

    public boolean isRest() {
        return REST.equals(kind);
    }

    public List<Pitch> pitches() {
        List<Pitch> out = new ArrayList<>(notes.size());
        for (NoteHead n : notes) {
            out.add(n.pitch);
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Event e && Objects.equals(e.kind, kind) && Objects.equals(e.offset, offset)
                && Objects.equals(e.duration, duration) && Objects.equals(e.notes, notes)
                && Objects.equals(e.noteType, noteType) && e.dots == dots
                && Objects.equals(e.tuplet, tuplet) && e.grace == grace && e.measureRest == measureRest
                && Objects.equals(e.lyrics, lyrics) && Objects.equals(e.articulations, articulations)
                && Objects.equals(e.confidence, confidence) && Objects.equals(e.bbox, bbox)
                && Objects.equals(e.provenance, provenance);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, offset, duration, notes, noteType, dots, tuplet, grace, measureRest,
                lyrics, articulations, confidence, bbox, provenance);
    }

    @Override
    public String toString() {
        return "Event(" + kind + " @" + offset + " dur " + duration + " " + pitches() + ")";
    }
}

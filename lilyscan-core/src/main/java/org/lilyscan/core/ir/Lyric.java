package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/** One syllable of one verse under a note. */
@JsonPropertyOrder({"text", "verse", "syllabic", "extend"})
public final class Lyric {
    public String text;
    public int verse = 1;
    /** {@code single}, {@code begin}, {@code middle} or {@code end}. */
    public String syllabic = "single";
    public boolean extend;

    public Lyric() {}

    public Lyric(String text) {
        this.text = text;
    }

    public Lyric(String text, int verse, String syllabic, boolean extend) {
        this.text = text;
        this.verse = verse;
        this.syllabic = syllabic;
        this.extend = extend;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Lyric l && Objects.equals(l.text, text) && l.verse == verse
                && Objects.equals(l.syllabic, syllabic) && l.extend == extend;
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, verse, syllabic, extend);
    }
}

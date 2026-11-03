package org.lilyscan.eval.synth;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lilyscan.core.ir.ChordSymbol;
import org.lilyscan.core.ir.Clef;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.ir.Lyric;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.MusicXmlWriter;
import org.lilyscan.core.ir.NoteHead;
import org.lilyscan.core.ir.Part;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Staff;
import org.lilyscan.core.ir.Voice;
import org.lilyscan.core.testing.Repo;

/**
 * The generator writes the same music as {@code lilyscan/synth/generate.py}: every piece of
 * {@code eval/corpus/seed.json}, written as MusicXML by Lilyscan and read back, equals the Python
 * generator's piece read back from its own MusicXML ({@code tests/parity/synth/seed.json}),
 * measure by measure: notes, rests, ties, tuplets, lyrics, chord symbols, clefs, keys and meters.
 */
class SynthParityTest {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    static String frac(Frac f) {
        return f.numerator() + "/" + f.denominator();
    }

    /** {@code scripts/java_synth_reference.py}'s summary, for a Java score. */
    static ObjectNode summary(Score score) {
        ObjectNode out = F.objectNode();
        out.put("title", score.title);
        out.put("composer", score.composer);
        ArrayNode staves = out.putArray("staves");
        for (Part part : score.parts) {
            for (Staff staff : part.staves) {
                ObjectNode s = staves.addObject();
                s.put("part", part.name);
                s.put("abbreviation", part.abbreviation);
                ArrayNode measures = s.putArray("measures");
                for (Measure m : staff.measures) {
                    ObjectNode mo = measures.addObject();
                    mo.put("number", m.number);
                    ArrayNode clefs = mo.putArray("clefs");
                    for (Clef c : m.clefs) clefs.addArray().add(c.sign).add(c.line).add(c.octaveChange).add(frac(c.offset));
                    if (m.key == null) mo.putNull("key"); else mo.putArray("key").add(m.key.fifths).add(m.key.mode);
                    if (m.time == null) mo.putNull("time"); else mo.putArray("time").add(m.time.beats).add(m.time.beatType);
                    if (m.rightBarline == null) mo.putNull("barline"); else mo.put("barline", m.rightBarline.style);
                    ArrayNode chords = mo.putArray("chords");
                    // music21 writes a half-diminished chord as minor-seventh plus a flat-fifth
                    // <degree>, which the readers do not keep (Generator.QUALITIES).
                    for (ChordSymbol c : m.chordSymbols) {
                        chords.addArray().add(frac(c.offset)).add(c.root)
                                .add("half-diminished".equals(c.kind) ? "minor-seventh" : c.kind);
                    }
                    ArrayNode voices = mo.putArray("voices");
                    List<Voice> sorted = new ArrayList<>(m.voices);
                    sorted.sort(Comparator.comparingInt(v -> v.number));
                    for (Voice v : sorted) {
                        if (v.events.isEmpty()) continue;
                        ArrayNode vo = voices.addArray();
                        for (Event e : v.events) {
                            ArrayNode eo = vo.addArray().add(frac(e.offset)).add(frac(e.duration)).add(e.kind);
                            ArrayNode notes = eo.addArray();
                            for (NoteHead n : e.notes) {
                                notes.add(n.pitch.step + n.pitch.alter + "/" + n.pitch.octave + (n.tieStart ? "~" : "")
                                        + (n.tieStop ? "^" : ""));
                            }
                            if (e.tuplet == null) eo.addNull(); else eo.addArray().add(e.tuplet.actual()).add(e.tuplet.normal());
                            ArrayNode lyrics = eo.addArray();
                            for (Lyric l : e.lyrics) lyrics.addArray().add(l.text).add(l.syllabic);
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test
    void samePiecesAsThePythonGenerator() throws Exception {
        JsonNode expected = Repo.readJson(Repo.parity("synth/seed.json"));
        List<CorpusSpec.Piece> specs = CorpusSpec.load(Repo.root().resolve("eval/corpus/seed.json"));
        assertEquals(expected.size(), specs.size());
        List<String> differ = new ArrayList<>();
        for (CorpusSpec.Piece spec : specs) {
            String xml = MusicXmlWriter.toMusicXml(Generator.build(spec), MusicXmlWriter.Options.ENGRAVING);
            Score back = MusicXml.parse(xml.getBytes(StandardCharsets.UTF_8), "ground-truth");
            ObjectNode got = summary(back);
            JsonNode want = expected.get(spec.id());
            if (!want.equals(got)) {
                differ.add(spec.id() + ": " + firstDifference(want, got, ""));
            }
        }
        assertTrue(differ.isEmpty(), differ.size() + " piece(s) differ:\n" + String.join("\n", differ));
    }

    static String firstDifference(JsonNode a, JsonNode b, String path) {
        if (a.equals(b)) return null;
        if (a.isObject() && b.isObject()) {
            for (var it = a.fieldNames(); it.hasNext();) {
                String f = it.next();
                String d = firstDifference(a.get(f), b.path(f), path + "." + f);
                if (d != null) return d;
            }
        } else if (a.isArray() && b.isArray() && a.size() == b.size()) {
            for (int i = 0; i < a.size(); i++) {
                String d = firstDifference(a.get(i), b.get(i), path + "[" + i + "]");
                if (d != null) return d;
            }
        }
        return path + ": python " + new ObjectMapper().valueToTree(a) + " java " + b;
    }
}

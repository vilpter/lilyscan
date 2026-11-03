package org.lilyscan.eval.synth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A corpus spec ({@code eval/corpus/*.json}): {@code {"pieces": [{"id", "category", "seed",
 * "measures", "language"?, "work"?, "first"?, "engraver"?, "source"?, "file"?}, ...]}}, as
 * {@code lilyscan/synth/corpus.py} reads it. A piece with a {@code work} is an excerpt of a real
 * work from {@code source}: {@code music21} (the default, as in Python), {@code openscore-lieder}
 * or {@code openscore-quartets} ({@link Sources}).
 */
public final class CorpusSpec {
    private CorpusSpec() {}

    /** One piece of a spec. */
    public record Piece(String id, String category, int seed, int measures, String language, String work,
            int first, String engraver, String source, String file) {

        /** The work's file in its source: {@code file} when the spec names it, else {@code <work>.mxl}. */
        public String path() {
            return file != null ? file : work;
        }

        public boolean generated() {
            return work == null;
        }

        /** The manifest entry (Python's {@code PieceSpec.to_dict}, plus a non-default source). */
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("category", category);
            out.put("seed", seed);
            out.put("measures", measures);
            out.put("language", language);
            if (work != null) {
                out.put("work", work);
                out.put("first", first);
            }
            if (!engraver.equals("musicxml2ly")) out.put("engraver", engraver);
            if (work != null && !source.equals(Sources.MUSIC21)) out.put("source", source);
            return out;
        }
    }

    public static List<Piece> load(Path path) {
        JsonNode data;
        try {
            data = new ObjectMapper().readTree(path.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Piece> pieces = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode p : data.path("pieces")) {
            String work = p.path("work").isTextual() ? p.get("work").asText() : null;
            String engraver = p.path("engraver").asText("musicxml2ly");
            if (!engraver.equals("musicxml2ly") && !engraver.equals("lilyscan")) {
                throw new IllegalArgumentException("unknown engraver '" + engraver + "'");
            }
            String source = p.path("source").asText(Sources.MUSIC21);
            if (!Sources.KNOWN.contains(source)) throw new IllegalArgumentException("unknown source '" + source + "'");
            Piece piece = new Piece(p.get("id").asText(), p.get("category").asText(), p.get("seed").asInt(),
                    p.get("measures").asInt(), p.path("language").isTextual() ? p.get("language").asText() : null,
                    work, p.path("first").asInt(1), engraver, source,
                    p.path("file").isTextual() ? p.get("file").asText() : null);
            if (!ids.add(piece.id())) throw new IllegalArgumentException("duplicate piece ids in " + path);
            pieces.add(piece);
        }
        return pieces;
    }
}

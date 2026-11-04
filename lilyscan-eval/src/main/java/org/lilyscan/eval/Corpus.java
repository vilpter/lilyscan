package org.lilyscan.eval;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.lilyscan.core.ir.IrJson;

/**
 * A built evaluation corpus (port of the reading half of {@code lilyscan/synth/corpus.py}): the
 * items its {@code manifest.json} lists, each with its ground truth and input variants. Corpora
 * are built by the Python {@code lilyscan corpus build} (design-java.md, JM8).
 */
public final class Corpus {
    private Corpus() {}

    public static final List<String> VARIANTS = List.of("pdf", "png", "scan", "photo");
    private static final Map<String, String> FILES = Map.of("pdf", "score.pdf", "png", "score.png", "scan", "scan.png", "photo", "photo.png");

    /** One piece of the corpus. */
    public record Item(String id, String category, String language, Path root) {
        public Path groundTruth() {
            return root.resolve("ground_truth.musicxml");
        }

        public Path input(String variant) {
            return root.resolve(FILES.get(variant));
        }
    }

    /** The items listed in an already-built corpus's manifest. */
    public static List<Item> load(Path dir) {
        JsonNode data;
        try {
            data = IrJson.mapper().readTree(dir.resolve("manifest.json").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Item> out = new ArrayList<>();
        for (JsonNode i : data.get("items")) {
            JsonNode lang = i.get("language");
            out.add(new Item(i.get("id").asText(), i.get("category").asText(),
                    lang == null || lang.isNull() ? null : lang.asText(), dir.resolve(i.get("id").asText())));
        }
        return out;
    }
}

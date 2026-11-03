package org.lilyscan.eval.synth;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.lilyscan.core.Versions;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.MusicXmlWriter;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.lilypond.Compile;
import org.lilyscan.core.lilypond.Generate;
import org.lilyscan.core.runtime.Config;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.util.ProcessRunner;

/**
 * {@code lilyscan corpus build} ({@code lilyscan/synth/corpus.py}): for every piece of a spec, the
 * ground truth ({@code ground_truth.musicxml}), its engraving ({@code score.pdf}, a 300 DPI
 * {@code score.png}), a simulated scan ({@code scan.png}) and photo ({@code photo.png}), and a
 * {@code manifest.json} the evaluation harness reads.
 */
public final class CorpusBuilder {
    private CorpusBuilder() {}

    public static final List<String> VARIANTS = List.of("pdf", "png", "scan", "photo");
    static final Map<String, String> FILES = Map.of("pdf", "score.pdf", "png", "score.png", "scan", "scan.png",
            "photo", "photo.png");
    static final int PNG_DPI = 300;
    // Corpus pieces are sized to fit one page, so each raster variant is a single image.
    private static final String ONE_PAGE = "\n\\paper { page-count = #1 }\n";

    /** A built piece. */
    public record Item(CorpusSpec.Piece spec, Path root, String source, String rights) {
        Path groundTruth() {
            return root.resolve("ground_truth.musicxml");
        }

        boolean complete() {
            if (!Files.isRegularFile(groundTruth())) return false;
            for (String v : VARIANTS) if (!Files.isRegularFile(root.resolve(FILES.get(v)))) return false;
            return true;
        }
    }

    /**
     * Builds the pieces of {@code specPath} into {@code outDir} ({@code only}: just these ids;
     * complete pieces are kept unless {@code force}). Real works are fetched into {@code cache}.
     */
    public static List<Item> build(Path specPath, Path outDir, Set<String> only, boolean force, Path cache,
            Settings s, Consumer<String> log) throws IOException {
        List<Item> items = new ArrayList<>();
        for (CorpusSpec.Piece spec : CorpusSpec.load(specPath)) {
            Path root = outDir.resolve(spec.id());
            String[] origin = origin(spec, cache);
            Item item = new Item(spec, root, origin[0], origin[1]);
            if (only != null && !only.contains(spec.id())) {
                // Not rebuilt now, but kept in the manifest when it was built before.
                if (item.complete()) items.add(item);
                continue;
            }
            items.add(item);
            if (item.complete() && !force) continue;
            log.accept("building " + spec.id() + " (" + spec.category() + ")");
            Files.createDirectories(root);
            Score score = spec.generated() ? Generator.build(spec)
                    : Excerpt.of(MusicXml.load(Sources.fetch(spec.source(), spec.path(), cache), "ground-truth"),
                            spec.first(), spec.measures());
            if (!spec.generated() && (score.title == null || score.title.isBlank())) score.title = spec.work();
            MusicXmlWriter.write(score, item.groundTruth(), MusicXmlWriter.Options.ENGRAVING);
            Path png = engrave(item.groundTruth(), root, s, spec.engraver());
            Degrade.Image clean = Degrade.load(png);
            Degrade.savePng(Degrade.scan(clean, degradeSeed(spec, 1)), root.resolve("scan.png"));
            Degrade.savePng(Degrade.photo(clean, degradeSeed(spec, 2)), root.resolve("photo.png"));
        }
        writeManifest(outDir, specPath, items);
        return items;
    }

    /**
     * The seed of a piece's scan ({@code k = 1}) or photo ({@code k = 2}): Python's
     * {@code seed * 10 + k}, mixed with the piece id, so that pieces sharing a seed (every real
     * work has seed 0) are still degraded differently.
     */
    static long degradeSeed(CorpusSpec.Piece spec, int k) {
        return (spec.seed() * 10L + k) ^ ((long) spec.id().hashCode() << 32);
    }

    /** {@code [source, rights]} for the manifest. */
    private static String[] origin(CorpusSpec.Piece spec, Path cache) {
        if (spec.generated()) return new String[] {"generated", null};
        String label = switch (spec.source()) {
            case Sources.MUSIC21 -> "music21 corpus: ";
            case Sources.LIEDER -> "OpenScore Lieder: ";
            case Sources.QUARTETS -> "OpenScore String Quartets: ";
            default -> spec.source() + ": ";
        };
        Path file = Sources.fetch(spec.source(), spec.path(), cache);
        return new String[] {label + spec.work(), Sources.rights(spec.source(), file)};
    }

    /** Engraves into {@code outDir/score.pdf} and {@code outDir/score.png} (one page each); returns the PNG. */
    static Path engrave(Path musicxml, Path outDir, Settings s, String engraver) throws IOException {
        Path ly;
        if (engraver.equals("musicxml2ly")) {
            ly = outDir.resolve("score.ly");
            List<String> cmd = new ArrayList<>(Compile.lilypondTool("musicxml2ly", s));
            cmd.addAll(List.of("--output", ly.toString(), musicxml.toString()));
            ProcessRunner.Result r = ProcessRunner.run(cmd, null, s.lilypondTimeoutS());
            if (r.returncode() != 0 || !Files.isRegularFile(ly)) {
                throw new IOException("musicxml2ly failed for " + musicxml.getFileName() + ":\n" + tail(r.stderr()));
            }
        } else {
            Path root = outDir.resolve("ly");
            Generate.writeProject(MusicXml.load(musicxml, "ground-truth"), root);
            ly = root.resolve("main.ly");
        }
        Files.writeString(ly, Files.readString(ly, StandardCharsets.UTF_8) + ONE_PAGE, StandardCharsets.UTF_8);
        Compile.Result result = Compile.compileLy(ly, outDir, List.of("pdf", "png"), false,
                List.of("-dresolution=" + PNG_DPI), s);
        List<Path> pngs = result.outputs().stream().filter(p -> p.toString().endsWith(".png")).sorted().toList();
        List<Path> pdfs = result.outputs().stream().filter(p -> p.toString().endsWith(".pdf")).toList();
        if (!result.ok() || pngs.size() != 1 || pdfs.size() != 1) {
            throw new IOException("LilyPond did not produce one PDF and one PNG page for " + musicxml.getFileName()
                    + " (pdf=" + pdfs.size() + ", png=" + pngs.size() + "):\n" + tail(result.log()));
        }
        Path pdf = pdfs.get(0), png = pngs.get(0);
        if (!pdf.getFileName().toString().equals("score.pdf")) {
            pdf = Files.move(pdf, outDir.resolve("score.pdf"), StandardCopyOption.REPLACE_EXISTING);
        }
        if (!png.getFileName().toString().equals("score.png")) {
            png = Files.move(png, outDir.resolve("score.png"), StandardCopyOption.REPLACE_EXISTING);
        }
        return png;
    }

    private static String tail(String s) {
        return s.length() <= 2000 ? s : s.substring(s.length() - 2000);
    }

    static Path writeManifest(Path outDir, Path specPath, List<Item> items) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("generator", "lilyscan " + Versions.APP);
        manifest.put("lilypond", Config.LILYPOND_VERSION);
        manifest.put("spec", specPath.getFileName().toString());
        manifest.put("license", "Generated pieces: project content (AGPL-3.0-or-later); lyric texts are "
                + "public domain. Corpus works: see each item's source and rights.");
        List<Map<String, Object>> list = new ArrayList<>();
        for (Item item : items) {
            Map<String, Object> m = new LinkedHashMap<>(item.spec().toMap());
            m.put("source", item.source());
            if (item.rights() != null) m.put("rights", item.rights());
            m.put("ground_truth", outDir.relativize(item.groundTruth()).toString().replace('\\', '/'));
            Map<String, String> inputs = new LinkedHashMap<>();
            for (String v : VARIANTS) inputs.put(v, outDir.relativize(item.root().resolve(FILES.get(v))).toString().replace('\\', '/'));
            m.put("inputs", inputs);
            list.add(m);
        }
        manifest.put("items", list);
        Path path = outDir.resolve("manifest.json");
        try {
            Files.createDirectories(outDir);
            String json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(manifest);
            Files.writeString(path, json + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }
}

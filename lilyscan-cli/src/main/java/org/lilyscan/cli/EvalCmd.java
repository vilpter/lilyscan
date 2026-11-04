package org.lilyscan.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.eval.Calibrate;
import org.lilyscan.eval.Corpus;
import org.lilyscan.eval.Harness;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code lilyscan eval run | calibrate}: the evaluation harness (Python {@code lilyscan eval}). */
@Command(name = "eval", description = "Evaluation harness.", subcommands = {EvalCmd.Run.class, EvalCmd.CalibrateCmd.class})
final class EvalCmd implements Runnable {
    @Override
    public void run() {
        picocli.CommandLine.usage(this, System.out);
    }

    @Command(name = "run", description = "Run Audiveris on the corpus and score the output.")
    static final class Run implements Callable<Integer> {
        @Option(names = "--corpus", description = "A corpus built by `lilyscan corpus build`.")
        Path corpus = Path.of("eval/corpus/seed");

        @Option(names = "--work", description = "Cache of engine output.")
        Path work = Path.of("eval/work");

        @Option(names = "--variants", arity = "1..*", description = "pdf, png, scan, photo.")
        List<String> variants = new ArrayList<>(Corpus.VARIANTS);

        @Option(names = "--only", arity = "0..*", description = "Piece ids.")
        List<String> only = new ArrayList<>();

        @Option(names = "--category", arity = "0..*", description = "Piece categories.")
        List<String> category = new ArrayList<>();

        @Option(names = "--jobs", description = "Parallel Audiveris processes.")
        int jobs = 1;

        @Option(names = "--no-reuse", description = "Ignore cached engine output.")
        boolean noReuse;

        @Option(names = "--lilypond", description = "Also run engine output -> LilyPond -> Q1-Q5.")
        boolean lilypond;

        @Option(names = "--repair", description = "Apply the Stage 5 repair rules before scoring.")
        boolean repair;

        @Option(names = "--prepare", description = "Give scans and photos to the engine through Stage 1 (new engine runs).")
        boolean prepare;

        @Option(names = "--label")
        String label = "latest";

        @Option(names = "--out", description = "Results directory (default eval/results/<label>).")
        Path out;

        @Option(names = "--notes", description = "Free text recorded with the results.")
        String notes = "";

        @Override
        public Integer call() throws Exception {
            for (String v : variants) {
                if (!Corpus.VARIANTS.contains(v)) throw new IllegalArgumentException("unknown variant " + v);
            }
            List<Corpus.Item> items = new ArrayList<>(Corpus.load(corpus));
            if (!only.isEmpty()) items.removeIf(i -> !Set.copyOf(only).contains(i.id()));
            if (!category.isEmpty()) items.removeIf(i -> !Set.copyOf(category).contains(i.category()));
            Settings s = Settings.fromEnv();
            List<Harness.ItemResult> results = Harness.run(items, work, variants, jobs, s,
                    new Harness.Options(!noReuse, lilypond, repair, prepare));
            Path dir = out != null ? out : Path.of("eval/results").resolve(label);
            Path path = Harness.writeResults(results, dir, label, notes, s);
            System.out.println(Files.readString(path));
            return 0;
        }
    }

    @Command(name = "calibrate", description = "Fit Lilyscan's confidence model on cached engine output.")
    static final class CalibrateCmd implements Callable<Integer> {
        @Option(names = "--corpus", arity = "1..*")
        List<Path> corpus = new ArrayList<>(List.of(Path.of("eval/corpus/seed"), Path.of("eval/corpus/repertoire")));

        @Option(names = "--work", description = "Cache of engine output.")
        Path work = Path.of("eval/work");

        @Option(names = "--out")
        Path out = Path.of("lilyscan-core/src/main/resources/org/lilyscan/core/repair/confidence.json");

        @Override
        @SuppressWarnings("unchecked")
        public Integer call() throws Exception {
            Settings s = Settings.fromEnv();
            Map<String, List<Calibrate.Sample>> corpora = new LinkedHashMap<>();
            for (Path c : corpus) corpora.put(c.getFileName().toString(), Calibrate.collect(Corpus.load(c), work, s));
            Map<String, Object> model = Calibrate.calibrate(corpora, out);
            for (Object o : (List<Object>) ((Map<String, Object>) model.get("trained")).get("evaluation")) {
                Map<String, Object> row = (Map<String, Object>) o;
                System.out.println(row.get("corpus") + ": " + row.get("events") + " events, ECE out of fold " + row.get("ece_out_of_fold")
                        + " (in sample " + row.get("ece_in_sample") + ", engine grades " + row.get("ece_engine_grade") + ")");
            }
            System.out.println("model: " + out);
            return 0;
        }
    }
}

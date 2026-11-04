package org.lilyscan.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.eval.synth.CorpusBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code lilyscan corpus build}: an evaluation corpus from a spec (Python {@code lilyscan corpus build}). */
@Command(name = "corpus", description = "Evaluation corpora.", subcommands = {CorpusCmd.Build.class})
final class CorpusCmd implements Runnable {
    @Override
    public void run() {
        picocli.CommandLine.usage(this, System.out);
    }

    @Command(name = "build", mixinStandardHelpOptions = true,
            description = "Generate or excerpt the spec's pieces, engrave them and simulate scans and photos.")
    static final class Build implements Callable<Integer> {
        @Option(names = "--spec", description = "Corpus spec (default eval/corpus/seed.json).")
        Path spec = Path.of("eval/corpus/seed.json");

        @Option(names = "--out", description = "Output folder (default: the spec's name beside it).")
        Path out;

        @Option(names = "--only", arity = "0..*", description = "Build only these piece ids.")
        List<String> only = new ArrayList<>();

        @Option(names = "--force", description = "Rebuild pieces that are already complete.")
        boolean force;

        @Option(names = "--cache", description = "Where fetched works are kept (default eval/corpus/cache).")
        Path cache = Path.of("eval/corpus/cache");

        @Override
        public Integer call() throws Exception {
            Path target = out != null ? out : spec.resolveSibling(spec.getFileName().toString().replaceFirst("\\.json$", ""));
            var items = CorpusBuilder.build(spec, target, only.isEmpty() ? null : new HashSet<>(only), force, cache,
                    Settings.fromEnv(), System.out::println);
            System.out.println(items.size() + " pieces in " + target);
            return 0;
        }
    }
}

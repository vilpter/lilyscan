package org.lilyscan.core.runtime;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.lilyscan.core.util.Py;

/**
 * Settings from the environment (port of {@code Settings} in {@code lilyscan/runtime/config.py}).
 *
 * <p>Differences from the Python deployment (design-java.md D16, D18, D22): Audiveris is bundled
 * and runs in a forked worker JVM, so {@code AUDIVERIS_BIN} is optional — set, it names an
 * external Audiveris launcher to use instead; {@code AUDIVERIS_MAX_HEAP} sets the worker's heap
 * (the Python image rewrote the launcher config for it); jobs run in-process, so
 * {@code LILYSCAN_DISPATCH} is {@code inline} and Redis is gone.
 *
 * @param dataDir where jobs are kept
 * @param lilypondBin the LilyPond executable
 * @param audiverisBin an external Audiveris launcher, or null for the bundled engine
 * @param lilypondTimeoutS one LilyPond compile
 * @param audiverisTimeoutS the least time an engine run gets
 * @param audiverisTimeoutPerPageS more for long books: this much per page
 * @param audiverisStepTimeoutS the most one engine step may take on a sheet
 * @param ocrLanguages the default OCR languages (D10)
 * @param dispatch how jobs run ({@code inline})
 * @param webDir the web UI's static files, or null for the bundled ones
 * @param audiverisMaxHeap the engine worker's heap limit, e.g. {@code 3G}
 */
public record Settings(
        Path dataDir,
        String lilypondBin,
        String audiverisBin,
        double lilypondTimeoutS,
        double audiverisTimeoutS,
        double audiverisTimeoutPerPageS,
        int audiverisStepTimeoutS,
        String ocrLanguages,
        String dispatch,
        Path webDir,
        String audiverisMaxHeap) {

    private static final Pattern HEAP = Pattern.compile("^[1-9][0-9]*[mMgG]$");

    public static Settings fromEnv() {
        return fromEnv(System.getenv());
    }

    public static Settings fromEnv(Map<String, String> e) {
        String bin = e.get("AUDIVERIS_BIN");
        String web = e.get("LILYSCAN_WEB_DIR");
        return new Settings(
                Path.of(e.getOrDefault("LILYSCAN_DATA_DIR", "data")).toAbsolutePath().normalize(),
                e.getOrDefault("LILYPOND_BIN", "lilypond"),
                bin == null || bin.isBlank() ? null : bin,
                Py.parseFloat(e.getOrDefault("LILYPOND_TIMEOUT_S", "120")),
                Py.parseFloat(e.getOrDefault("AUDIVERIS_TIMEOUT_S", "900")),
                Py.parseFloat(e.getOrDefault("AUDIVERIS_TIMEOUT_PER_PAGE_S", "240")),
                Py.parseInt(e.getOrDefault("AUDIVERIS_STEP_TIMEOUT_S", "300")),
                Config.parseOcrLanguages(e.getOrDefault("LILYSCAN_OCR_LANGUAGES", Config.DEFAULT_OCR_LANGUAGES)),
                dispatch(e.getOrDefault("LILYSCAN_DISPATCH", "inline")),
                web == null || web.isBlank() ? null : Path.of(web).toAbsolutePath().normalize(),
                heap(e.getOrDefault("AUDIVERIS_MAX_HEAP", "3G")));
    }

    private static String dispatch(String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            v = "inline";
        }
        if (v.equals("rq")) {
            throw new IllegalArgumentException("LILYSCAN_DISPATCH=rq was the Python deployment's Redis queue; "
                    + "the Java server runs jobs in-process: use inline");
        }
        if (!v.equals("inline")) {
            throw new IllegalArgumentException("LILYSCAN_DISPATCH must be inline, got '" + value + "'");
        }
        return v;
    }

    private static String heap(String value) {
        String v = value.strip();
        if (!HEAP.matcher(v).matches()) {
            throw new IllegalArgumentException("AUDIVERIS_MAX_HEAP must look like 3G or 1500m, got '" + value + "'");
        }
        return v;
    }
}

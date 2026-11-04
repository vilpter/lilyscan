package org.lilyscan.core.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.lilyscan.core.Versions;

/**
 * Single home for versions, keys and thresholds. Port of {@code lilyscan/runtime/config.py}.
 *
 * <p>Every recognition threshold lives here and is expressed in staff-space units, never pixels.
 * Environment-dependent settings are read once via {@link Settings#fromEnv}.
 */
public final class Config {
    private Config() {}

    /** Pinned toolchain versions (D5, D7); see {@link Versions}. */
    public static final String LILYPOND_VERSION = Versions.LILYPOND;
    public static final String AUDIVERIS_VERSION = Versions.AUDIVERIS;

    /** OCR languages, as Tesseract codes joined with "+". The defaults cover lyrics in English,
     *  Latin, German and French (D10). */
    public static final String DEFAULT_OCR_LANGUAGES = "eng+lat+deu+fra";
    private static final Pattern OCR_SPEC =
            Pattern.compile("^[a-z]{3}(?:_[a-z]+)?(?:\\+[a-z]{3}(?:_[a-z]+)?)*$");

    /** Audiveris silently ignores unknown -constant keys, so every key used here is covered by a
     *  behavioural test against the pinned release. */
    public static final String AUDIVERIS_OCR_LANGUAGES_KEY = "org.audiveris.omr.text.Language.defaultSpecification";
    /** Audiveris gives up on a sheet when one step takes longer than this (120 s by default). Pages
     *  mixing small and normal noteheads can take longer in HEADS on a busy machine. */
    public static final String AUDIVERIS_STEP_TIMEOUT_KEY = "org.audiveris.omr.Main.sheetStepTimeOut";

    /** Stage 0: born-digital PDF pages are rendered at this resolution for the engine. */
    public static final int PDF_RENDER_DPI = 400;
    /** A PDF page is a scan when one image covers at least this share of it. */
    public static final double PDF_RASTER_COVER = 0.8;

    // Stage 1 (input preparation). Lengths are in staff spaces (interlines) unless noted.
    /** Target interline range in pixels; pages outside it are rescaled to the middle of it. */
    public static final double PREPARE_INTERLINE_MIN = 16.0;
    public static final double PREPARE_INTERLINE_MAX = 32.0;
    /** Below this interline (pixels) a page is too coarse to read. */
    public static final double PREPARE_MIN_INTERLINE = 9.0;
    /** Staff lines left curved after dewarping (RMS deviation from straight): warn above this. */
    public static final double PREPARE_MAX_CURVATURE = 0.2;
    /** Horizontal segments at least this long (share of page width) are candidate staff lines. */
    public static final double PREPARE_LINE_MIN_WIDTH = 0.25;
    /** The page must cover at least this share of a photo for perspective correction. */
    public static final double PREPARE_MIN_PAGE_AREA = 0.3;
    /** A page outline leaving more than this share of its staff lines outside cuts the music. */
    public static final double PREPARE_MAX_CUT_LINES = 0.02;

    /** Normalize and validate an OCR language spec such as {@code eng+lat+deu}. */
    public static String parseOcrLanguages(String spec) {
        List<String> codes = new ArrayList<>();
        for (String c : spec.strip().toLowerCase(Locale.ROOT).replace(",", "+").split("\\+", -1)) {
            if (!c.isEmpty()) {
                codes.add(c);
            }
        }
        String normalized = String.join("+", new LinkedHashSet<>(codes));
        if (!OCR_SPEC.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "invalid OCR language spec '" + spec + "'; expected e.g. 'eng+lat+deu'");
        }
        return normalized;
    }
}

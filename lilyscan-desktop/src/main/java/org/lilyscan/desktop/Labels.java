package org.lilyscan.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The words the web UI shows, and its small formatting rules, shared by the desktop panes. */
final class Labels {
    private Labels() {}

    /** What a running job is doing, by stage. */
    static final Map<String, String> STAGES = Map.of(
            "upload", "Queued",
            "prepare", "Straightening the pages",
            "engine", "Recognizing the music (Audiveris, about 30 s per page)",
            "import", "Reading the engine's result",
            "repair", "Repairing parts, clefs, and rhythm",
            "lilypond", "Engraving with LilyPond and running checks",
            "recompile", "Recompiling your edits",
            "combine", "Combining the parts");

    /** Review-list issue kinds. */
    static final Map<String, String> ISSUES = Map.of(
            "compile", "does not compile",
            "bar-check", "bar check",
            "rhythm", "rhythm",
            "range", "range",
            "repaired", "repaired",
            "low-confidence", "low confidence");

    /** Transpositions offered when combining parts: value, label. */
    static final List<String[]> INTERVALS = List.of(
            new String[] {"", "as written"},
            new String[] {"M2", "up a major 2nd"},
            new String[] {"-M2", "down a major 2nd"},
            new String[] {"m3", "up a minor 3rd"},
            new String[] {"-m3", "down a minor 3rd"},
            new String[] {"P4", "up a 4th"},
            new String[] {"-P4", "down a 4th"},
            new String[] {"P5", "up a 5th"},
            new String[] {"-P5", "down a 5th"},
            new String[] {"P8", "up an octave"},
            new String[] {"-P8", "down an octave"});

    static final String[] CONCERT = {"concert", "at concert pitch"};

    static String stage(String stage) {
        return STAGES.getOrDefault(stage, stage);
    }

    static String issue(String kind) {
        return ISSUES.getOrDefault(kind, kind);
    }

    static boolean busy(JsonNode job) {
        String s = job.path("status").asText();
        return s.equals("queued") || s.equals("running");
    }

    /** "Combined: <title>" or the uploads' names without their {@code 00-} prefixes. */
    static String jobLabel(JsonNode job) {
        JsonNode combine = job.path("options").path("combine");
        if (combine.isObject()) {
            String title = combine.path("title").isTextual() ? combine.get("title").asText() : "";
            return "Combined: " + (title.isEmpty() ? "score" : title);
        }
        List<String> names = new ArrayList<>();
        job.path("inputs").forEach(n -> names.add(n.asText().replaceFirst("^\\d\\d-", "")));
        return String.join(", ", names);
    }

    static String created(JsonNode job) {
        return job.path("created_at").asText().replace("T", " ").replace("+00:00", " UTC");
    }

    /** The colour class of a confidence: high (≥ 0.8), mid (≥ 0.5), low, or unknown. */
    static String confidence(JsonNode c) {
        if (c == null || c.isNull() || !c.isNumber()) return "unknown";
        double v = c.asDouble();
        if (v >= 0.8) return "high";
        if (v >= 0.5) return "mid";
        return "low";
    }

    private static final Pattern TEXTEDIT = Pattern.compile("^textedit://(.*):(\\d+):(\\d+):(\\d+)$");

    /** A LilyPond point-and-click link as {@code "<file under ly/>:<line>"}, or {@code null}. */
    static String linkTarget(String href) {
        if (href == null) return null;
        Matcher m = TEXTEDIT.matcher(URLDecoder.decode(href.replace("+", "%2B"), StandardCharsets.UTF_8));
        if (!m.matches()) return null;
        String path = m.group(1).replace('\\', '/');
        int at = path.lastIndexOf("/ly/");
        return at < 0 ? null : path.substring(at + 4) + ":" + m.group(2);
    }

    /** {@code "3/2"} quarters from the barline as a beat counted from 1: {@code "2.5"}. */
    static String beat(String offset) {
        String[] nd = offset.split("/");
        double n = Double.parseDouble(nd[0]);
        double d = nd.length > 1 ? Double.parseDouble(nd[1]) : 0;
        double value = 1 + (d != 0 ? n / d : n);
        if (value == Math.rint(value)) return String.valueOf((long) value);
        String s = new BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }

    /** The editor's file order: parts, then chords, then main, then the rest; by name within. */
    static int fileOrder(String f) {
        if (f.startsWith("parts/")) return 0;
        if (f.equals("chords.ly")) return 1;
        if (f.equals("main.ly")) return 2;
        return 3;
    }

    /** A tab's title: the file name without {@code parts/} and {@code .ly}. */
    static String tab(String f) {
        return f.replaceFirst("^parts/", "").replaceFirst("\\.ly$", "");
    }

    /** "m. 1, 2, 3" for up to six measures, else the first five "and N more". */
    static String measures(List<String> ms) {
        if (ms.size() <= 6) return String.join(", ", ms);
        return String.join(", ", ms.subList(0, 5)) + " and " + (ms.size() - 5) + " more";
    }
}

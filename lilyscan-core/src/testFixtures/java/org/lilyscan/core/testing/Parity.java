package org.lilyscan.core.testing;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Comparisons against the Python references, with readable failure messages. */
public final class Parity {
    private Parity() {}

    /** QA checks added after the Python reference was retired: not in its reports. */
    public static final java.util.Set<String> JAVA_ONLY_CHECKS = java.util.Set.of("Q10");

    /**
     * {@code node} without the {@link #JAVA_ONLY_CHECKS} in any {@code checks} array, so a report
     * can still be compared with the Python reference for the checks both have.
     */
    public static JsonNode withoutJavaOnlyChecks(JsonNode node) {
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode o) {
            var it = o.properties().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getKey().equals("checks") && e.getValue() instanceof com.fasterxml.jackson.databind.node.ArrayNode a) {
                    for (int i = a.size() - 1; i >= 0; i--) {
                        if (JAVA_ONLY_CHECKS.contains(a.get(i).path("id").asText())) a.remove(i);
                    }
                }
                withoutJavaOnlyChecks(e.getValue());
            }
        } else if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode a) {
            for (JsonNode n : a) withoutJavaOnlyChecks(n);
        }
        return node;
    }

    /** Fails with the first differing line (and a little context) when the texts differ. */
    public static void assertSameText(String expected, String actual, String label) {
        if (expected.equals(actual)) {
            return;
        }
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        int n = Math.min(e.length, a.length);
        int i = 0;
        while (i < n && e[i].equals(a[i])) {
            i++;
        }
        StringBuilder sb = new StringBuilder(label + ": differs from the Python reference at line "
                + (i + 1) + " (expected " + e.length + " lines, got " + a.length + ")\n");
        for (int k = Math.max(0, i - 6); k < i; k++) {
            sb.append("    ").append(e[k]).append('\n');
        }
        sb.append("  - ").append(i < e.length ? e[i] : "<end>").append('\n');
        sb.append("  + ").append(i < a.length ? a[i] : "<end>").append('\n');
        fail(sb.toString());
    }

    /** One reference case: a name, an input path and the reader's source label. */
    public record Case(String name, Path input, String source, Path reference) {}

    /** A generator case: a reference score.json and the project Python generated from it. */
    public record ProjectCase(String name, Path score, Path reference) {
        /** The QA report Python produced for the same project (written when LilyPond was found). */
        public Path qa() {
            return sibling(".qa.json");
        }

        /** Another reference written for the same score, e.g. {@code .musicxml} or {@code .review.json}. */
        public Path sibling(String suffix) {
            return reference.resolveSibling(reference.getFileName().toString().replace(".project.json", suffix));
        }
    }

    /** The committed generator cases, plus the local sweep when -PparitySweep is set. */
    public static List<ProjectCase> projectCases() {
        List<ProjectCase> out = new ArrayList<>();
        for (JsonNode e : Repo.readJson(Repo.parity("lilypond/manifest.json"))) {
            String name = e.get("name").asText();
            out.add(new ProjectCase(name, Repo.parity(e.get("score").asText()),
                    Repo.parity("lilypond/" + name + ".project.json")));
        }
        String sweep = System.getProperty("lilyscan.paritySweep");
        if (sweep != null && !sweep.isBlank()) {
            Path dir = Path.of(sweep);
            for (JsonNode e : Repo.readJson(dir.resolve("manifest.json"))) {
                String name = e.get("name").asText();
                out.add(new ProjectCase("sweep/" + name, dir.resolve(name + ".score.json"),
                        dir.resolve(name + ".project.json")));
            }
        }
        return out;
    }

    /** The committed MusicXML cases, plus the local sweep when -PparitySweep is set. */
    public static List<Case> musicXmlCases() {
        List<Case> out = new ArrayList<>();
        for (JsonNode e : Repo.readJson(Repo.parity("musicxml/manifest.json"))) {
            String name = e.get("name").asText();
            out.add(new Case(name, Repo.path(e.get("input").asText()), e.get("source").asText(),
                    Repo.parity("musicxml/" + name + ".score.json")));
        }
        String sweep = System.getProperty("lilyscan.paritySweep");
        if (sweep != null && !sweep.isBlank()) {
            Path dir = Path.of(sweep);
            for (JsonNode e : Repo.readJson(dir.resolve("manifest.json"))) {
                String name = e.get("name").asText();
                out.add(new Case("sweep/" + name, Path.of(e.get("input").asText()),
                        e.get("source").asText(), dir.resolve(name + ".score.json")));
            }
        }
        return out;
    }
}

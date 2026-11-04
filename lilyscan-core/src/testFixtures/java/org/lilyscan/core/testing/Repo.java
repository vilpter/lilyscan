package org.lilyscan.core.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locations shared by the tests: the repository root, fixtures and parity references. */
public final class Repo {
    private Repo() {}

    /** The repository root (system property set by the Gradle build). */
    public static Path root() {
        String p = System.getProperty("lilyscan.repoRoot");
        if (p == null) {
            throw new IllegalStateException("lilyscan.repoRoot not set (run tests through Gradle)");
        }
        return Path.of(p);
    }

    public static Path path(String relative) {
        return root().resolve(relative);
    }

    /** {@code tests/parity/<relative>}: references written by scripts/java_parity.py. */
    public static Path parity(String relative) {
        return root().resolve("tests/parity").resolve(relative);
    }

    public static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode readJson(Path p) {
        try {
            return new ObjectMapper().readTree(p.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

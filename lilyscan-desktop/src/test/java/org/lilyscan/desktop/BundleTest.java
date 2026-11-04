package org.lilyscan.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An installed app finds the LilyPond and OCR models it ships with, laid out as jpackage installs
 * them on Linux ({@code <root>/bin/Lilyscan}, {@code <root>/lib/lilypond}, {@code <root>/lib/tessdata}),
 * and the environment still overrides them.
 */
class BundleTest {
    @TempDir Path root;

    @AfterEach
    void clear() {
        System.clearProperty("jpackage.app-path");
    }

    @Test
    void findsTheBundledTools() throws Exception {
        assertNull(Connection.Bundle.appDir(), "not an installed app");
        Files.createDirectories(root.resolve("bin"));
        Path ly = Files.createDirectories(root.resolve("lib/lilypond/bin")).resolve("lilypond");
        Files.writeString(ly, "#!/bin/sh\n");
        Files.setPosixFilePermissions(ly, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createDirectories(root.resolve("lib/tessdata"));
        Files.createDirectories(root.resolve("lib/app"));
        System.setProperty("jpackage.app-path", root.resolve("bin/Lilyscan").toString());

        assertEquals(root.resolve("lib"), Connection.Bundle.appDir());
        assertEquals(ly, Connection.Bundle.lilypond());
        assertEquals(root.resolve("lib/tessdata"), Connection.Bundle.tessdata());

        Path data = root.resolve("data");
        assertEquals(ly.toString(), Connection.localSettings(Map.of(), data).lilypondBin());
        assertEquals("/usr/local/bin/lilypond",
                Connection.localSettings(Map.of("LILYPOND_BIN", "/usr/local/bin/lilypond"), data).lilypondBin());
        assertEquals(data, Connection.localSettings(Map.of(), data).dataDir());
    }
}

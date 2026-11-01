package org.lilyscan.core.lilypond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lilyscan.core.runtime.Config;
import org.lilyscan.core.runtime.Settings;

/** Port of tests/test_lilypond_compile.py. */
class CompileTest {

    static final String LOG = """
            Processing `/tmp/x/score.ly'
            /tmp/x/score.ly:7:14: warning: bar check failed at: 1/4
              g4 a b c
            /tmp/x/old.ly:2:1: warning: barcheck failed at: 3/4
            C:\\Users\\me\\score.ly:12:3: error: syntax error, unexpected '}'
            warning: no \\version statement found
            fatal error: failed files: "score.ly"
            """;

    @Test
    void parsesDiagnostics() {
        List<Compile.Diagnostic> d = Compile.parseDiagnostics(LOG);
        assertEquals(List.of("warning", "warning", "error", "warning", "fatal error"),
                d.stream().map(Compile.Diagnostic::severity).toList());
        assertTrue(d.get(0).isBarcheck() && d.get(0).line() == 7 && d.get(0).column() == 14);
        assertTrue(d.get(1).isBarcheck()); // 2.24 wording
        assertEquals("C:\\Users\\me\\score.ly", d.get(2).file());
        assertEquals(12, d.get(2).line());
        assertNull(d.get(3).file());
        assertFalse(d.get(3).isBarcheck());
        assertTrue(d.get(4).isError());
    }

    @Test
    void helloWorldPinsVersion() {
        assertTrue(Compile.HELLO_WORLD.contains("\\version \"" + Config.LILYPOND_VERSION + "\""));
    }

    @Test
    void programmingErrorsAreWarnings() {
        List<Compile.Diagnostic> d = Compile.parseDiagnostics("programming error: Multi measure rest seems misplaced.");
        assertEquals(1, d.size());
        assertTrue(d.get(0).isInternal() && !d.get(0).isError());
    }

    static Settings lilypond() {
        Settings s = Settings.fromEnv();
        Assumptions.assumeTrue(Compile.lilypondVersion(s) != null, "LilyPond not found (set LILYPOND_BIN)");
        return s;
    }

    static Compile.Result compile(Path src, Path out, List<String> formats) {
        return Compile.compileLy(src, out, formats, false, List.of(), lilypond());
    }

    @Test
    @Tag("lilypond")
    void installedVersionMatchesPin() {
        assertEquals(Config.LILYPOND_VERSION, Compile.lilypondVersion(lilypond()));
    }

    @Test
    @Tag("lilypond")
    void compilesHelloWorld(@TempDir Path tmp) throws Exception {
        Path src = Files.writeString(tmp.resolve("hello.ly"), Compile.HELLO_WORLD);
        Compile.Result r = compile(src, tmp.resolve("out"), List.of("pdf", "svg"));
        assertTrue(r.ok(), r.log());
        Set<String> suffixes = r.outputs().stream().map(p -> p.getFileName().toString().replaceAll(".*\\.", "."))
                .collect(Collectors.toSet());
        assertEquals(Set.of(".pdf", ".svg"), suffixes);
    }

    @Test
    @Tag("lilypond")
    void compilesFromAnotherDirectory(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src"));
        Path src = Files.writeString(tmp.resolve("src/hello.ly"), Compile.HELLO_WORLD);
        Compile.Result r = compile(src, tmp.resolve("build/out"), List.of("pdf"));
        assertTrue(r.ok(), r.log());
        assertTrue(Files.isRegularFile(tmp.resolve("build/out/hello.pdf")));
    }

    @Test
    @Tag("lilypond")
    void reportsBarcheck(@TempDir Path tmp) throws Exception {
        Path src = Files.writeString(tmp.resolve("bad.ly"),
                "\\version \"" + Config.LILYPOND_VERSION + "\"\n\\fixed c' { \\time 4/4 c4 d e | f1 | }\n");
        Compile.Result r = compile(src, tmp.resolve("out"), List.of("pdf"));
        assertTrue(r.ok()); // a failed bar check is a warning, not an error
        assertTrue(r.warnings().stream().anyMatch(Compile.Diagnostic::isBarcheck), r.log());
    }

    @Test
    @Tag("lilypond")
    void reportsSyntaxError(@TempDir Path tmp) throws Exception {
        Path src = Files.writeString(tmp.resolve("broken.ly"), "\\version \"" + Config.LILYPOND_VERSION + "\"\n{ c4 d e } }\n");
        Compile.Result r = compile(src, tmp.resolve("out"), List.of("pdf"));
        assertFalse(r.ok());
        assertFalse(r.errors().isEmpty());
    }

    @Test
    @Tag("lilypond")
    void aRunThatWritesNoPdfLeavesNoOldOne(@TempDir Path tmp) throws Exception {
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(out.resolve("empty.pdf"), "%PDF from an earlier run");
        Files.writeString(out.resolve("empty.mid"), "MThd");
        Path src = Files.writeString(tmp.resolve("empty.ly"), "\\version \"" + Config.LILYPOND_VERSION + "\"\n");
        Compile.Result r = compile(src, out, List.of("pdf"));
        assertFalse(r.ok());
        assertEquals(List.of(), r.outputs());
        assertEquals(List.of("LilyPond wrote no PDF file"), r.errors().stream().map(Compile.Diagnostic::message).toList());
        assertFalse(Files.exists(out.resolve("empty.pdf")));
        assertFalse(Files.exists(out.resolve("empty.mid")));
    }
}

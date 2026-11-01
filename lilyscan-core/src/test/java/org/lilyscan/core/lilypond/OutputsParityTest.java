package org.lilyscan.core.lilypond;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.MusicXml;
import org.lilyscan.core.ir.MusicXmlWriter;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.testing.Parity;
import org.lilyscan.core.testing.Repo;

/** The MusicXML export and the single-file LilyPond source must be the Python ones, byte for byte. */
class OutputsParityTest {

    private static Stream<Parity.ProjectCase> cases(String suffix) {
        return Parity.projectCases().stream().filter(c -> Files.isRegularFile(c.sibling(suffix)));
    }

    @TestFactory
    Stream<DynamicTest> musicXmlExport() {
        return cases(".musicxml").map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            Score score = IrJson.readScore(c.score());
            Parity.assertSameText(Repo.read(c.sibling(".musicxml")), MusicXmlWriter.toMusicXml(score),
                    c.name() + " musicxml");
        }));
    }

    /** Reading the export back gives the same score (the writer is the reader's inverse). */
    @TestFactory
    Stream<DynamicTest> musicXmlRoundTrip() {
        return cases(".musicxml").map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            Path tmp = Files.createTempFile("lilyscan-export-", ".musicxml");
            MusicXmlWriter.write(IrJson.readScore(c.score()), tmp);
            Score once = MusicXml.load(tmp, "musicxml");
            MusicXmlWriter.write(once, tmp);
            Score twice = MusicXml.load(tmp, "musicxml");
            Parity.assertSameText(IrJson.toJson(once), IrJson.toJson(twice), c.name() + " round trip");
        }));
    }

    @TestFactory
    Stream<DynamicTest> singleFile() {
        return cases(".single.ly").map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            LyProject project = Generate.generateProject(IrJson.readScore(c.score()));
            Parity.assertSameText(Repo.read(c.sibling(".single.ly")), SingleFile.singleFile(project.files()),
                    c.name() + " single");
        }));
    }
}

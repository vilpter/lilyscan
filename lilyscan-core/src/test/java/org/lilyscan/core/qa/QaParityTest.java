package org.lilyscan.core.qa;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.lilypond.Compile;
import org.lilyscan.core.lilypond.Generate;
import org.lilyscan.core.lilypond.LyProject;
import org.lilyscan.core.review.Review;
import org.lilyscan.core.runtime.Settings;
import org.lilyscan.core.testing.Parity;
import org.lilyscan.core.testing.Repo;

/** The QA report (Q1-Q5, Q9, with a real LilyPond compile) and the review model built from it
 *  must be the Python ones, byte for byte. */
@Tag("lilypond")
class QaParityTest {

    @TestFactory
    Stream<DynamicTest> matchesPythonChecks() {
        Settings s = Settings.fromEnv();
        Assumptions.assumeTrue(Compile.lilypondVersion(s) != null, "LilyPond not found (set LILYPOND_BIN)");
        return Parity.projectCases().stream().filter(c -> Files.isRegularFile(c.qa()))
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> {
                    Score score = IrJson.readScore(c.score());
                    Path root = Files.createTempDirectory("lilyscan-qa-");
                    LyProject project = Generate.writeProject(score, root);
                    Checks.QaReport report = Checks.runChecks(score, root, project, s);
                    String json = PyJson.write(IrJson.mapper().valueToTree(report.toMap()), PyJson.Style.jsonDumps(1)) + "\n";
                    Parity.assertSameText(Repo.read(c.qa()), json, c.name() + " qa");
                    Path review = c.sibling(".review.json");
                    if (Files.isRegularFile(review)) {
                        String r = PyJson.write(IrJson.mapper().valueToTree(Review.build(score, project, report)),
                                PyJson.Style.JSON_DUMPS_COMPACT);
                        Parity.assertSameText(Repo.read(review), r, c.name() + " review");
                    }
                }));
    }
}

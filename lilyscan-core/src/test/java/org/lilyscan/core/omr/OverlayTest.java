package org.lilyscan.core.omr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lilyscan.core.ir.IrJson;
import org.lilyscan.core.testing.Repo;

class OverlayTest {
    @TempDir Path out;

    @Test
    void onePagePerSheetDrawnOnTheBinarizedImage() throws Exception {
        String name = "piano-two-voices";
        OmrBook book = OmrBookJson.load(Repo.readJson(Repo.parity("omr/" + name + ".book.json")));
        var score = IrJson.readScore(Repo.parity("omr/" + name + ".score.json"));
        Path omr = Repo.root().resolve("eval/fixtures/omr/5.11.0/" + name + "/book.omr");
        List<Path> pages = Overlay.render(score, book, omr, out);
        assertEquals(List.of(out.resolve("page-1.png")), pages);
        BufferedImage img = ImageIO.read(pages.get(0).toFile());
        assertEquals(book.sheets().get(0).width(), img.getWidth());
        assertEquals(book.sheets().get(0).height(), img.getHeight());
        // A located event's box is drawn in its confidence colour.
        var e = score.parts.get(0).staves.get(0).measures.get(0).voices.get(0).events.get(0);
        int rgb = img.getRGB((int) e.bbox.x - 2, (int) e.bbox.y + 1) & 0xffffff;
        assertEquals(Overlay.confidenceColour(e.confidence).getRGB() & 0xffffff, rgb);
    }
}

package org.lilyscan.core.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageFitTest {
    @TempDir Path dir;

    /** One A4 page with five "staff lines" from x0 to x1 (points). */
    private Path staff(String name, float x0, float x1) throws Exception {
        Path pdf = dir.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < 5; i++) {
                    cs.moveTo(x0, 700 - 7 * i);
                    cs.lineTo(x1, 700 - 7 * i);
                }
                cs.stroke();
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    @Test
    void aSystemInsideTheMarginsFits() throws Exception {
        Checks.CheckResult r = Checks.pageFitCheck(staff("fits.pdf", 56, 539));
        assertTrue(r.passed(), r.summary());
        assertEquals("Q10", r.id());
    }

    @Test
    void aSystemRunningOffTheRightEdgeFails() throws Exception {
        Checks.CheckResult r = Checks.pageFitCheck(staff("wide.pdf", 56, 1500));
        assertFalse(r.passed());
        assertEquals(1, r.details().size());
        assertEquals(1, r.details().get(0).get("page"));
        // 1500 - 595.28 pt = 904.7 pt = 319.2 mm
        assertEquals(319.2, (double) r.details().get(0).get("overflow_mm"), 0.2);
    }
}

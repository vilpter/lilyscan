package org.lilyscan.core.ingest;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lilyscan.core.testing.Repo;

class BookTest {
    @TempDir Path dir;

    private static Path repro(String name) {
        return Repo.root().resolve("docs/upstream/audiveris/repro/" + name);
    }

    /** Page kinds and render sizes as PyMuPDF gives them for the same files. */
    @Test
    void bornDigitalPdfPagesRenderAt400Dpi() throws Exception {
        for (var c : Map.of("last-measure.pdf", new int[] {3400, 4400}, "satb-01.pdf", new int[] {3308, 4678}).entrySet()) {
            List<PdfPages.Page> pages = PdfPages.pages(repro(c.getKey()));
            assertEquals(1, pages.size(), c.getKey());
            assertEquals("vector", pages.get(0).kind());
            assertEquals(c.getValue()[0], pages.get(0).pixels().getWidth(), c.getKey());
            assertEquals(c.getValue()[1], pages.get(0).pixels().getHeight(), c.getKey());
        }
    }

    /** A scanned page (one image over the whole page) renders at the image's resolution. */
    @Test
    void scannedPdfPageKeepsItsResolution() throws Exception {
        Path pdf = dir.resolve("scan.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            doc.addPage(new PDPage(PDRectangle.LETTER)); // empty: left out
            BufferedImage img = new BufferedImage(1700, 2200, BufferedImage.TYPE_BYTE_GRAY); // 200 DPI
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(LosslessFactory.createFromImage(doc, img), 0, 0, 612, 792);
            }
            doc.save(pdf.toFile());
        }
        List<PdfPages.Page> pages = PdfPages.pages(pdf);
        assertEquals(1, pages.size());
        assertEquals("raster", pages.get(0).kind());
        assertEquals(1700, pages.get(0).pixels().getWidth());
    }

    @Test
    void assemblesEveryPageIntoOneTiff() throws Exception {
        Path png = repro("no-clef.png");
        Path bogus = Files.writeString(dir.resolve("notes.txt"), "x");
        Book book = Book.assemble(List.of(repro("satb-01.pdf"), png, bogus, repro("two-movements.pdf")), dir.resolve("prepared"));
        assertEquals(dir.resolve("prepared/pages.tif"), book.prepared);
        assertNull(book.uploaded);
        assertEquals(4, book.pages.size());
        assertEquals(Map.of("input", "satb-01.pdf", "page", 0, "kind", "vector", "born_digital", true,
                "rendered_dpi", 400, "passed", true), book.pages.get(0));
        assertEquals("image", book.pages.get(1).get("kind"));
        assertEquals(Map.of("input", "notes.txt", "error", "unsupported input notes.txt", "passed", false),
                book.pages.get(2));
        assertEquals(3, Book.imagePages(book.prepared).size());

        Path kept = Book.withoutPages(book.prepared, List.of(1, 3), dir.resolve("kept"));
        List<BufferedImage> pages = Book.imagePages(kept);
        assertEquals(2, pages.size());
        assertEquals(3308, pages.get(0).getWidth());
        assertEquals(dir.resolve("kept/pages.tif"), kept);
    }

    @Test
    void withoutPagesOfAPdf() throws Exception {
        Path kept = Book.withoutPages(repro("satb-01.pdf"), List.of(1), dir);
        assertEquals(1, PdfPages.pages(kept).size());
    }
}

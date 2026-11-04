package org.lilyscan.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The web UI's formatting rules (services/web/app.js), as the desktop app applies them. */
class LabelsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void beats() {
        assertEquals("1", Labels.beat("0"));
        assertEquals("2.5", Labels.beat("3/2"));
        assertEquals("1.33", Labels.beat("1/3"));
        assertEquals("1.67", Labels.beat("2/3"));
        assertEquals("3", Labels.beat("2/1"));
        // JavaScript's toFixed rounds an exact tie up: (2.125).toFixed(2) is "2.13".
        assertEquals("2.13", Labels.beat("9/8"));
    }

    @Test
    void links() {
        assertEquals("parts/violin.ly:12", Labels.linkTarget("textedit:///data/jobs/abc/ly/parts/violin.ly:12:3:4"));
        assertEquals("main.ly:4", Labels.linkTarget("textedit://C:%5Cdata%5Cjobs%5Cx%5Cly%5Cmain.ly:4:1:2"));
        assertNull(Labels.linkTarget("textedit:///elsewhere/main.ly:4:1:2"));
        assertNull(Labels.linkTarget("https://example.com"));
        assertNull(Labels.linkTarget(null));
    }

    @Test
    void jobsAndFiles() throws Exception {
        assertEquals("violin.pdf, cello.png", Labels.jobLabel(JSON.readTree(
                "{\"inputs\": [\"00-violin.pdf\", \"01-cello.png\"], \"options\": {}}")));
        assertEquals("Combined: Duo", Labels.jobLabel(JSON.readTree(
                "{\"inputs\": [], \"options\": {\"combine\": {\"title\": \"Duo\"}}}")));
        assertEquals("Combined: score", Labels.jobLabel(JSON.readTree(
                "{\"inputs\": [], \"options\": {\"combine\": {\"title\": null}}}")));
        assertEquals("high", Labels.confidence(JSON.readTree("0.8")));
        assertEquals("mid", Labels.confidence(JSON.readTree("0.5")));
        assertEquals("low", Labels.confidence(JSON.readTree("0.49")));
        assertEquals("unknown", Labels.confidence(JSON.readTree("null")));
        List<String> files = new java.util.ArrayList<>(List.of("main.ly", "layout/score.ly", "chords.ly", "parts/b.ly", "parts/a.ly"));
        files.sort(java.util.Comparator.comparingInt(Labels::fileOrder).thenComparing(java.util.Comparator.naturalOrder()));
        assertEquals(List.of("parts/a.ly", "parts/b.ly", "chords.ly", "main.ly", "layout/score.ly"), files);
        assertEquals("violin", Labels.tab("parts/violin.ly"));
        assertEquals("1, 2, 3, 4, 5 and 2 more", Labels.measures(List.of("1", "2", "3", "4", "5", "6", "7")));
    }
}

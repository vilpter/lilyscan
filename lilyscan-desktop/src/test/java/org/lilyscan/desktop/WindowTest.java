package org.lilyscan.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The window itself (needs a display; CI runs it under Xvfb): open a transcribed job, select a
 * measure from the review list, the engraved notes and the editor caret, and check that every
 * pane follows; then edit, save and recompile. Screenshots go to {@code build/screenshots/}.
 */
class WindowTest {
    @TempDir Path data;
    @TempDir Path uploads;
    private Connection.Local local;
    private MainWindow window;

    @BeforeEach
    void start() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no display");
        local = FakeServer.start(data);
        SwingUtilities.invokeAndWait(() -> {
            FlatLightLaf.setup();
            window = new MainWindow(data);
            window.setSize(1500, 950);
            window.setVisible(true);
            window.attach(local);
        });
    }

    @AfterEach
    void stop() throws Exception {
        if (window != null) SwingUtilities.invokeAndWait(window::dispose);
        if (local != null) local.close();
    }

    private static <T> T onEdt(Callable<T> c) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(c.call());
            } catch (Exception e) {
                err.set(e);
            }
        });
        if (err.get() != null) throw err.get();
        return out.get();
    }

    private static void until(Callable<Boolean> condition, String what) throws Exception {
        long end = System.currentTimeMillis() + 120_000;
        while (!onEdt(condition)) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for " + what);
            Thread.sleep(100);
        }
    }

    private void screenshot(String name) throws Exception {
        BufferedImage img = onEdt(() -> {
            BufferedImage b = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
            window.getRootPane().paint(b.getGraphics());
            return b;
        });
        Path dir = Path.of("build/screenshots");
        Files.createDirectories(dir);
        ImageIO.write(img, "png", dir.resolve(name + ".png").toFile());
    }

    private int caretLine() throws Exception {
        return onEdt(() -> window.job().editor().getLineOfOffset(window.job().editor().getCaretPosition()) + 1);
    }

    @Test
    void reviewSelectEditRecompile() throws Exception {
        Path pdf = Files.copy(FakeServer.PDF, uploads.resolve("piano.pdf"));
        JsonNode job = local.api().createJob(List.of(pdf), null, true);
        String id = job.get("id").asText();
        assertEquals("done", FakeServer.waitFor(local.api(), id).get("status").asText());
        until(() -> window.home().isShowing(), "the home page");
        screenshot("home");

        SwingUtilities.invokeAndWait(() -> window.showJob(id));
        JobPanel panel = window.job();
        until(panel::loaded, "the review");

        // From the review list: the editor jumps to the measure's line, the score highlights it.
        int count = onEdt(() -> panel.reviewList().getModel().getSize());
        List<String> linked = onEdt(() -> panel.score().linkedMeasures());
        String fromList = null;
        for (int i = 0; i < count && fromList == null; i++) {
            String mid = panel.reviewList().getModel().getElementAt(i);
            ScorePane.Spot spot = onEdt(() -> panel.score().spotOf(mid));
            // A flagged measure with engraved notes on the page (an overfull line runs past it).
            if (linked.contains(mid) && spot.point().x < spot.page().getWidth()) fromList = mid;
        }
        String target = fromList != null ? fromList : linked.get(linked.size() / 2);
        if (fromList != null) {
            int index = onEdt(() -> ((javax.swing.DefaultListModel<String>) panel.reviewList().getModel()).indexOf(target));
            SwingUtilities.invokeAndWait(() -> panel.reviewList().setSelectedIndex(index));
        } else {
            SwingUtilities.invokeAndWait(() -> panel.select(target, "review"));
        }
        JsonNode m = onEdt(() -> panel.measure(target));
        until(() -> target.equals(panel.selectedMeasure()) && m.get("file").asText().equals(panel.currentFile()), "the selection");
        until(() -> window.job().editor().getLineOfOffset(window.job().editor().getCaretPosition()) + 1 == m.get("line").asInt(),
                "the editor to reach the measure's line");
        assertEquals(target, onEdt(() -> panel.score().selected()));
        screenshot("review-selected");
        SwingUtilities.invokeAndWait(() -> panel.score().zoom(2.5));
        SwingUtilities.invokeAndWait(() -> panel.select(target, "review"));
        Thread.sleep(300);
        screenshot("review-zoomed");
        SwingUtilities.invokeAndWait(() -> panel.score().zoom(0));

        // A click on an engraved note of another measure selects that measure everywhere.
        String other = linked.get(0).equals(target) ? linked.get(1) : linked.get(0);
        ScorePane.Spot spot = onEdt(() -> panel.score().spotOf(other));
        assertNotNull(spot);
        SwingUtilities.invokeAndWait(() -> {
            for (var l : spot.page().getMouseListeners()) {
                l.mouseClicked(new MouseEvent(spot.page(), MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                        spot.point().x, spot.point().y, 1, false));
            }
        });
        JsonNode o = onEdt(() -> panel.measure(other));
        until(() -> other.equals(panel.selectedMeasure()), "the clicked measure");
        until(() -> window.job().editor().getLineOfOffset(window.job().editor().getCaretPosition()) + 1 == o.get("line").asInt(),
                "the editor to follow the click");

        // Moving the caret in the editor selects the measure on that line.
        SwingUtilities.invokeAndWait(() -> {
            try {
                var code = panel.editor();
                code.setCaretPosition(code.getLineStartOffset(m.get("line").asInt() - 1) + 1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        until(() -> target.equals(panel.selectedMeasure()) && target.equals(panel.score().selected()), "the caret's measure");
        assertEquals(m.get("line").asInt(), caretLine());

        // Edit, save and recompile.
        SwingUtilities.invokeAndWait(() -> panel.editor().append("\n% checked in the desktop app\n"));
        assertTrue(onEdt(() -> panel.saveButton().isEnabled()));
        String file = onEdt(panel::currentFile);
        SwingUtilities.invokeAndWait(() -> panel.saveButton().doClick());
        until(() -> !panel.saveButton().isEnabled(), "save to start");
        Thread.sleep(500);
        assertEquals("done", FakeServer.waitFor(local.api(), id).get("status").asText());
        assertTrue(local.api().ly(id, file).endsWith("% checked in the desktop app\n"));
        until(panel::loaded, "the review after recompiling");
        screenshot("after-recompile");
    }
}

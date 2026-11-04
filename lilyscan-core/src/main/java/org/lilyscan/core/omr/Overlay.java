package org.lilyscan.core.omr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lilyscan.core.ir.BBox;
import org.lilyscan.core.ir.Event;
import org.lilyscan.core.ir.Measure;
import org.lilyscan.core.ir.Score;
import org.lilyscan.core.ir.Voice;

/**
 * Debug overlays (port of {@code lilyscan/overlay.py}): IR boxes drawn on the page images the
 * engine analysed. Measures get a thin grey outline; events are boxed by confidence: green
 * (>= 0.8), amber (0.5-0.8), red (< 0.5), grey when unknown.
 *
 * <p>Drawn with Java2D rather than OpenCV, so the pixels differ from the Python images; the
 * files, colours, boxes and legend are the same.
 */
public final class Overlay {
    private Overlay() {}

    public static final Color HIGH = new Color(40, 160, 40);
    public static final Color MID = new Color(255, 165, 0);
    public static final Color LOW = new Color(220, 30, 30);
    public static final Color UNKNOWN = new Color(150, 150, 150);
    public static final Color MEASURE = new Color(200, 200, 200);
    public static final double HIGH_CONFIDENCE = 0.8;
    public static final double LOW_CONFIDENCE = 0.5;

    public static Color confidenceColour(Double confidence) {
        if (confidence == null) return UNKNOWN;
        if (confidence >= HIGH_CONFIDENCE) return HIGH;
        if (confidence >= LOW_CONFIDENCE) return MID;
        return LOW;
    }

    private static void rect(Graphics2D g, BBox box, Color colour, int thickness) {
        int pad = 2;
        int x0 = (int) box.x - pad, y0 = (int) box.y - pad;
        int x1 = (int) (box.x + box.w) + pad, y1 = (int) (box.y + box.h) + pad;
        g.setColor(colour);
        g.setStroke(new BasicStroke(thickness));
        g.drawRect(x0, y0, x1 - x0, y1 - y0);
    }

    private static void legend(Graphics2D g, int width) {
        double s = Math.max(1.0, width / 1000.0);
        String[] labels = {">= 0.8", "0.5 - 0.8", "< 0.5", "unknown"};
        Color[] colours = {HIGH, MID, LOW, UNKNOWN};
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, (int) Math.round(14 * s)));
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for (int k = 0; k < labels.length; k++) {
            int x = (int) (20 * s), y = (int) ((30 + 28 * k) * s);
            int box = (int) (18 * s);
            g.setColor(colours[k]);
            g.fillRect(x, y - box + (int) (4 * s), box, box);
            g.setColor(new Color(60, 60, 60));
            g.drawString(labels[k], x + box + (int) (10 * s), y);
        }
    }

    /** One {@code page-N.png} per sheet, drawn on the sheet's binarized image from the {@code .omr}. */
    public static List<Path> render(Score score, OmrBook book, Path omrPath, Path outDir) {
        List<Path> written = new ArrayList<>();
        try (ZipFile z = new ZipFile(omrPath.toFile())) {
            Files.createDirectories(outDir);
            for (int page = 0; page < book.sheets().size(); page++) {
                ZipEntry entry = z.getEntry("sheet#" + book.sheets().get(page).number() + "/BINARY.png");
                if (entry == null) continue;
                BufferedImage gray;
                try (InputStream in = z.getInputStream(entry)) {
                    gray = ImageIO.read(new ByteArrayInputStream(in.readAllBytes()));
                }
                if (gray == null) continue;
                BufferedImage canvas = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
                for (int y = 0; y < gray.getHeight(); y++) {
                    for (int x = 0; x < gray.getWidth(); x++) {
                        int rgb = gray.getRGB(x, y);
                        int v = (int) Math.round(0.299 * ((rgb >> 16) & 255) + 0.587 * ((rgb >> 8) & 255)
                                + 0.114 * (rgb & 255));
                        // Fade the ink so the coloured boxes stand out.
                        int f = (int) (255 - (255 - v) * 0.45f);
                        canvas.setRGB(x, y, (f << 16) | (f << 8) | f);
                    }
                }
                Graphics2D g = canvas.createGraphics();
                for (Score.PartStaff ps : score.staves()) {
                    for (Measure m : ps.staff().measures) {
                        if (m.bbox != null && m.bbox.page == page) rect(g, m.bbox, MEASURE, 1);
                        for (Voice v : m.voices) {
                            for (Event e : v.events) {
                                if (e.bbox != null && e.bbox.page == page) rect(g, e.bbox, confidenceColour(e.confidence), 2);
                            }
                        }
                    }
                }
                legend(g, canvas.getWidth());
                g.dispose();
                Path path = outDir.resolve("page-" + (page + 1) + ".png");
                ImageIO.write(canvas, "png", path.toFile());
                written.add(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return written;
    }
}

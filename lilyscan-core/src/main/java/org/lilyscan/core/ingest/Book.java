package org.lilyscan.core.ingest;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.lilyscan.core.prepare.Prepare;
import org.lilyscan.core.runtime.Config;
import static org.bytedeco.opencv.global.opencv_core.CV_8U;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imreadmulti;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwritemulti;

/**
 * Stages 0 and 1 (port of {@code lilyscan/ingest/book.py}): all of a job's pages, in order, as
 * one multi-page TIFF for the engine. Born-digital PDF pages are rendered at 400 DPI; scanned
 * PDF pages, photos and image scans go through Stage 1 ({@link Prepare}).
 *
 * <ul>
 *   <li>{@code pages.tif}: the pages as prepared;
 *   <li>{@code uploaded.tif}: the same pages as uploaded (but turned upright), written only when
 *       some page is a scan, so the engine can also read it and the better run be kept.
 * </ul>
 *
 * <p>Images are read and written with OpenCV's codecs, as the Python {@code cv2.imread} does.
 */
public final class Book {

    public static final Set<String> IMAGE_SUFFIXES = Set.of(".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tif", ".tiff");

    /** One report entry per page (or per input that could not be read). */
    public final List<Map<String, Object>> pages = new ArrayList<>();
    public Path prepared;
    /** Only when some page is a scan. */
    public Path uploaded;

    /** Some page is a scan (Stage 1 found no page edges in it). */
    public boolean scanLike() {
        return pages.stream().anyMatch(p -> Boolean.FALSE.equals(p.get("page_found")));
    }

    static String suffix(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** An 8-bit gray page. */
    record Gray(int w, int h, byte[] px) {
        static Gray of(Mat m) {
            Mat c = m.isContinuous() ? m : m.clone();
            byte[] px = new byte[c.cols() * c.rows()];
            c.data().get(px);
            return new Gray(c.cols(), c.rows(), px);
        }

        static Gray of(BufferedImage img) {
            if (img.getType() != BufferedImage.TYPE_BYTE_GRAY) throw new IllegalArgumentException("expected an 8-bit gray image");
            var raster = img.getRaster();
            byte[] px = new byte[img.getWidth() * img.getHeight()];
            raster.getDataElements(0, 0, img.getWidth(), img.getHeight(), px);
            return new Gray(img.getWidth(), img.getHeight(), px);
        }

        Mat mat() {
            Mat m = new Mat(h, w, CV_8U);
            m.data().put(px);
            return m;
        }

        BufferedImage image() {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            img.getRaster().setDataElements(0, 0, w, h, px);
            return img;
        }

        /** {@code np.rot90(pixels, k)}. */
        Gray rot90(int k) {
            k = ((k % 4) + 4) % 4;
            Gray g = this;
            for (int t = 0; t < k; t++) {
                byte[] r = new byte[g.px.length];
                int nw = g.h, nh = g.w;
                for (int y = 0; y < nh; y++) for (int x = 0; x < nw; x++) r[y * nw + x] = g.px[x * g.w + (g.w - 1 - y)];
                g = new Gray(nw, nh, r);
            }
            return g;
        }
    }

    /** Every page of an image file (all pages of a TIFF), grayscale, as {@code cv2} reads them. */
    static List<Gray> imagePages(Path path) throws IOException {
        List<Gray> out = new ArrayList<>();
        String s = suffix(path);
        if (s.equals(".tif") || s.equals(".tiff")) {
            MatVector pages = new MatVector();
            if (!imreadmulti(path.toString(), pages, IMREAD_GRAYSCALE) || pages.size() == 0) {
                throw new IOException("cannot read image " + path.getFileName());
            }
            for (long i = 0; i < pages.size(); i++) out.add(Gray.of(pages.get(i)));
            return out;
        }
        Mat img = imread(path.toString(), IMREAD_GRAYSCALE);
        if (img == null || img.empty()) throw new IOException("cannot read image " + path.getFileName());
        out.add(Gray.of(img));
        return out;
    }

    /** Writes {@code pages} as one multi-page TIFF ({@code cv2.imwritemulti}). */
    static void writeTiff(List<Gray> pages, Path dst) throws IOException {
        MatVector mats = new MatVector(pages.size());
        for (int i = 0; i < pages.size(); i++) mats.put(i, pages.get(i).mat());
        Files.deleteIfExists(dst);
        if (!imwritemulti(dst.toString(), mats)) throw new IOException("cannot write " + dst);
    }

    private record Kinded(String kind, Gray pixels) {}

    /** Prepares every page of {@code inputs} (in order) into {@code outDir}. */
    public static Book assemble(List<Path> inputs, Path outDir) {
        Book book = new Book();
        List<Gray> prepared = new ArrayList<>();
        List<Gray> uploaded = new ArrayList<>();
        for (Path path : inputs) {
            String suffix = suffix(path);
            List<Kinded> pages = new ArrayList<>();
            try {
                if (suffix.equals(".pdf")) {
                    for (PdfPages.Page p : PdfPages.pages(path)) pages.add(new Kinded(p.kind(), Gray.of(p.pixels())));
                } else if (IMAGE_SUFFIXES.contains(suffix)) {
                    for (Gray g : imagePages(path)) pages.add(new Kinded("image", g));
                } else {
                    throw new IOException("unsupported input " + path.getFileName());
                }
            } catch (IOException | RuntimeException e) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("input", path.getFileName().toString());
                entry.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                entry.put("passed", false);
                book.pages.add(entry);
                continue;
            }
            for (int number = 0; number < pages.size(); number++) {
                Kinded k = pages.get(number);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("input", path.getFileName().toString());
                entry.put("page", number);
                entry.put("kind", k.kind());
                if (k.kind().equals("vector")) {
                    entry.put("born_digital", true);
                    entry.put("rendered_dpi", Config.PDF_RENDER_DPI);
                    entry.put("passed", true);
                    prepared.add(k.pixels());
                    uploaded.add(k.pixels());
                } else {
                    Prepare.Result r = Prepare.preparePage(k.pixels().image(), path.getFileName().toString());
                    prepared.add(Gray.of(r.image()));
                    // Audiveris cannot read a page lying on its side, as uploaded or not.
                    int turns = Math.floorMod((int) Math.rint(r.report().rotated / 90), 4);
                    uploaded.add(k.pixels().rot90(turns));
                    entry.putAll(r.report().toMap());
                }
                book.pages.add(entry);
            }
        }
        if (prepared.isEmpty()) return book;
        try {
            Files.createDirectories(outDir);
            book.prepared = outDir.resolve("pages.tif");
            writeTiff(prepared, book.prepared);
            if (book.scanLike()) {
                book.uploaded = outDir.resolve("uploaded.tif");
                writeTiff(uploaded, book.uploaded);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return book;
    }

    /** A copy of the multi-page TIFF or PDF {@code path} holding only the pages {@code keep}
     *  (numbered from 1), under the same name in {@code outDir}. */
    public static Path withoutPages(Path path, List<Integer> keep, Path outDir) {
        try {
            Files.createDirectories(outDir);
            Path dst = outDir.resolve(path.getFileName().toString());
            if (suffix(path).equals(".pdf")) {
                PdfPages.select(path, keep, dst);
                return dst;
            }
            List<Gray> pages = imagePages(path);
            List<Gray> kept = new ArrayList<>();
            for (int n : keep) if (n > 0 && n <= pages.size()) kept.add(pages.get(n - 1));
            if (kept.isEmpty()) throw new IOException("cannot write " + dst);
            writeTiff(kept, dst);
            return dst;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

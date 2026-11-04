package org.lilyscan.core.ingest;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.lilyscan.core.runtime.Config;

/**
 * Stage 0 (port of {@code lilyscan/ingest/book.py}): all of a job's pages, in order, as one
 * multi-page TIFF for the engine ({@code pages.tif}), since a job's page geometry comes from one
 * Audiveris book.
 *
 * <p>Stage 1 (finding, straightening and cleaning photographed and scanned pages) arrives with
 * JM5. Until then such pages go to the engine as uploaded, their report entries carry no
 * preparation fields, and no {@code uploaded.tif} is written (there is no prepared form to
 * compare it with).
 */
public final class Book {

    public static final Set<String> IMAGE_SUFFIXES = Set.of(".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tif", ".tiff");

    /** One report entry per page (or per input that could not be read). */
    public final List<Map<String, Object>> pages = new ArrayList<>();
    public Path prepared;
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

    static BufferedImage gray(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_BYTE_GRAY) return img;
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, java.awt.Color.WHITE, null);
        g.dispose();
        return out;
    }

    /** Every page of an image file (all pages of a TIFF), grayscale. */
    static List<BufferedImage> imagePages(Path path) throws IOException {
        List<BufferedImage> out = new ArrayList<>();
        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) throw new IOException("cannot read image " + path.getFileName());
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int n = reader.getNumImages(true);
                for (int i = 0; i < n; i++) out.add(gray(reader.read(i)));
            } finally {
                reader.dispose();
            }
        }
        if (out.isEmpty()) throw new IOException("cannot read image " + path.getFileName());
        return out;
    }

    /** Writes {@code pages} as one multi-page, LZW-compressed TIFF. */
    static void writeTiff(List<BufferedImage> pages, Path dst) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        Files.deleteIfExists(dst);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(dst.toFile())) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType("LZW");
            writer.prepareWriteSequence(null);
            for (BufferedImage img : pages) writer.writeToSequence(new IIOImage(img, null, null), param);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private record Kinded(String kind, BufferedImage pixels) {}

    /** Every page of {@code inputs} (in order) into {@code outDir}. */
    public static Book assemble(List<Path> inputs, Path outDir) {
        Book book = new Book();
        List<BufferedImage> prepared = new ArrayList<>();
        for (Path path : inputs) {
            String suffix = suffix(path);
            List<Kinded> pages = new ArrayList<>();
            try {
                if (suffix.equals(".pdf")) {
                    for (PdfPages.Page p : PdfPages.pages(path)) pages.add(new Kinded(p.kind(), p.pixels()));
                } else if (IMAGE_SUFFIXES.contains(suffix)) {
                    for (BufferedImage img : imagePages(path)) pages.add(new Kinded("image", img));
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
                }
                entry.put("passed", true);
                prepared.add(k.pixels());
                book.pages.add(entry);
            }
        }
        if (prepared.isEmpty()) return book;
        try {
            Files.createDirectories(outDir);
            book.prepared = outDir.resolve("pages.tif");
            writeTiff(prepared, book.prepared);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + book.prepared, e);
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
            List<BufferedImage> pages = imagePages(path);
            List<BufferedImage> kept = new ArrayList<>();
            for (int n : keep) if (n > 0 && n <= pages.size()) kept.add(pages.get(n - 1));
            if (kept.isEmpty()) throw new IOException("cannot write " + dst);
            writeTiff(kept, dst);
            return dst;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

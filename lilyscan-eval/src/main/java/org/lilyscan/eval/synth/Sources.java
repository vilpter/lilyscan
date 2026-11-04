package org.lilyscan.eval.synth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lilyscan.core.ir.MusicXml;

/**
 * Where real repertoire comes from, each pinned to one revision so a spec always yields the same
 * excerpt (D11, D21). Files are fetched once into a cache folder.
 *
 * <ul>
 *   <li>{@code music21}: music21's bundled corpus (the Python builder's source), at the tag of the
 *       music21 the Python side uses. Each work keeps the rights statement its encoding carries.
 *   <li>{@code openscore-lieder}: OpenScore Lieder, 19th-century songs for voice and piano
 *       (German, French, English), CC0.
 *   <li>{@code openscore-quartets}: OpenScore String Quartets, CC0.
 * </ul>
 */
public final class Sources {
    private Sources() {}

    public static final String MUSIC21 = "music21";
    public static final String LIEDER = "openscore-lieder";
    public static final String QUARTETS = "openscore-quartets";
    public static final Set<String> KNOWN = Set.of(MUSIC21, LIEDER, QUARTETS);

    /** A source: its files' base URL at a pinned revision, and what its scores may be used under. */
    public record Source(String name, String baseUrl, String revision, String license) {}

    static final Map<String, Source> SOURCES = Map.of(
            MUSIC21, new Source(MUSIC21, "https://raw.githubusercontent.com/cuthbertLab/music21/v10.5.0/music21/corpus/",
                    "v10.5.0", null),
            LIEDER, new Source(LIEDER, "https://raw.githubusercontent.com/OpenScore/Lieder/"
                    + "38c5db510224d9facdc4b08d741fc788cfb58ea8/scores/", "38c5db510224d9facdc4b08d741fc788cfb58ea8",
                    "CC0 1.0 (OpenScore Lieder)"),
            QUARTETS, new Source(QUARTETS, "https://raw.githubusercontent.com/OpenScore/StringQuartets/"
                    + "2614b0b2958dd1ce3f52e172a43e5f4d0e29570f/scores/", "2614b0b2958dd1ce3f52e172a43e5f4d0e29570f",
                    "CC0 1.0 (OpenScore String Quartets)"));

    public static Source source(String name) {
        Source s = SOURCES.get(name);
        if (s == null) throw new IllegalArgumentException("unknown source '" + name + "'");
        return s;
    }

    /**
     * The work's file in the cache, fetched first if missing. {@code file} is its path in the
     * source (e.g. {@code bach/bwv66.6.mxl}); for music21, a work name without an extension is
     * taken as {@code <work>.mxl}.
     */
    public static Path fetch(String sourceName, String file, Path cache) {
        Source s = source(sourceName);
        String path = file.contains(".") && file.matches(".*\\.(mxl|xml|musicxml)$") ? file : file + ".mxl";
        // Cached under a hash of the path: works' paths carry non-ASCII names (Gesänge, „…“),
        // which the file system can only take under a UTF-8 locale.
        Path target = cache.resolve(s.name()).resolve(s.revision()).resolve(hash(path) + suffix(path));
        if (Files.isRegularFile(target)) return target;
        StringBuilder url = new StringBuilder(s.baseUrl());
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) url.append('/');
            url.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        try {
            Files.createDirectories(target.getParent());
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofMinutes(2)).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) {
                throw new IOException("HTTP " + r.statusCode() + " for " + url);
            }
            Path part = Files.createTempFile(target.getParent(), "fetch-", ".part");
            Files.write(part, r.body());
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("could not fetch " + sourceName + " " + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static String hash(String path) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String suffix(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot);
    }

    private static final Pattern RIGHTS = Pattern.compile("<rights(?:\\s[^>]*)?>([^<]*)</rights>");

    /**
     * What the work may be used under: the source's licence, and the {@code <rights>} statements
     * of its encoding (Python's {@code corpus_rights}); {@code unstated} when there are none.
     */
    public static String rights(String sourceName, Path file) {
        String xml = new String(MusicXml.readBytes(file), StandardCharsets.UTF_8);
        List<String> found = new ArrayList<>();
        Source s = source(sourceName);
        if (s.license() != null) found.add(s.license());
        Matcher m = RIGHTS.matcher(xml);
        while (m.find()) {
            String text = unescape(m.group(1)).strip();
            if (!text.isEmpty()) found.add(text);
        }
        return found.isEmpty() ? "unstated" : String.join("; ", found);
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#169;", "©").replace("&amp;", "&");
    }
}

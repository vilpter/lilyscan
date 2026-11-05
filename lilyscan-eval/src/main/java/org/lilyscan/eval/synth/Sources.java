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
 *   <li>{@code music21}: music21's bundled corpus (the Python builder's source), from the
 *       release the Python side used: its wheel on PyPI, checked against its SHA-256 and read
 *       from the zip. Each work keeps the rights statement its encoding carries.
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

    /**
     * A source: its files' base URL at a pinned revision, and what its scores may be used under.
     * When {@code archiveSha256} is set, {@code baseUrl} is one zip archive (checked against that
     * digest) and the files are read from inside it, under {@code archivePrefix}.
     */
    public record Source(String name, String baseUrl, String revision, String license,
            String archiveSha256, String archivePrefix) {
        Source(String name, String baseUrl, String revision, String license) {
            this(name, baseUrl, revision, license, null, null);
        }
    }

    static final Map<String, Source> SOURCES = Map.of(
            MUSIC21, new Source(MUSIC21, "https://files.pythonhosted.org/packages/81/4c/"
                    + "4f307e5ab6fbf59233813e58e07128a599c810dd974673449e132ce1432d/music21-10.5.0-py3-none-any.whl",
                    "v10.5.0", null, "9924eff5fbf58490e67cbf3b78a58ba53040e77c281930d354af32a747e94606",
                    "music21/corpus/"),
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
        try {
            Files.createDirectories(target.getParent());
            if (s.archiveSha256() != null) {
                extract(archive(s, cache), s.archivePrefix() + path, target);
                return target;
            }
            StringBuilder url = new StringBuilder(s.baseUrl());
            String[] parts = path.split("/");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) url.append('/');
                url.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
            }
            download(url.toString(), target);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("could not fetch " + sourceName + " " + path + ": " + e.getMessage(), e);
        }
    }

    /** The source's archive in the cache, downloaded and checked against its digest if missing. */
    private static Path archive(Source s, Path cache) throws IOException {
        Path archive = cache.resolve(s.name()).resolve(s.revision())
                .resolve(s.baseUrl().substring(s.baseUrl().lastIndexOf('/') + 1));
        if (Files.isRegularFile(archive) && sha256(archive).equals(s.archiveSha256())) return archive;
        Path part = archive.resolveSibling(archive.getFileName() + ".part");
        download(s.baseUrl(), part);
        String got = sha256(part);
        if (!got.equals(s.archiveSha256())) {
            Files.deleteIfExists(part);
            throw new IOException("SHA-256 of " + s.baseUrl() + " is " + got + ", expected " + s.archiveSha256());
        }
        Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return archive;
    }

    private static void extract(Path zip, String entryName, Path target) throws IOException {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            java.util.zip.ZipEntry entry = z.getEntry(entryName);
            if (entry == null) throw new IOException(entryName + " is not in " + zip.getFileName());
            Path part = Files.createTempFile(target.getParent(), "fetch-", ".part");
            try (var in = z.getInputStream(entry)) {
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static void download(String url, Path target) throws IOException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        try {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
            Path part = Files.createTempFile(target.getParent(), "fetch-", ".part");
            Files.write(part, r.body());
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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

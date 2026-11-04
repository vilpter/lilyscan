package org.lilyscan.server.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.lilyscan.core.json.PyJson;

/**
 * Job records in SQLite (the Python {@code services/lilyscan_app/jobs.py}): the same file
 * ({@code jobs.sqlite} in the data dir), schema and column encodings, so a data dir written by
 * either implementation opens in the other.
 */
public final class JobStore implements AutoCloseable {

    /** The job states, stored by their lower-case names. */
    public enum Status {
        QUEUED, RUNNING, DONE, FAILED;

        public String value() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static Status of(String value) {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** One job row. {@code options} and {@code report} are JSON objects ({@code report} may be null). */
    public record Job(
            String id,
            String createdAt,
            Status status,
            String stage,
            List<String> inputs,
            ObjectNode options,
            String error,
            ObjectNode report) {

        /** The API's job JSON (Python {@code Job.to_dict}). */
        public ObjectNode toJson() {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            o.put("id", id);
            o.put("created_at", createdAt);
            o.put("status", status.value());
            o.put("stage", stage);
            var arr = o.putArray("inputs");
            inputs.forEach(arr::add);
            o.set("options", options);
            if (error == null) o.putNull("error"); else o.put("error", error);
            if (report == null) o.putNull("report"); else o.set("report", report);
            return o;
        }
    }

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY,
                created_at TEXT NOT NULL,
                status TEXT NOT NULL,
                stage TEXT NOT NULL,
                inputs TEXT NOT NULL,
                options TEXT NOT NULL,
                error TEXT,
                report TEXT
            )""";
    private static final int BUSY_TIMEOUT_MS = 30_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dataDir;
    private final Connection db;

    public JobStore(Path dataDir) {
        this.dataDir = dataDir;
        try {
            Files.createDirectories(dataDir);
            db = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("jobs.sqlite"));
            try (Statement st = db.createStatement()) {
                st.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
                useWal(st);
                st.execute(SCHEMA);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open job store in " + dataDir, e);
        }
    }

    /** Switches to write-ahead logging once (the mode is stored in the file); a busy switch is retried. */
    private static void useWal(Statement st) throws SQLException {
        for (int attempt = 0; ; attempt++) {
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                if (!rs.getString(1).equalsIgnoreCase("wal")) st.execute("PRAGMA journal_mode=WAL");
                return;
            } catch (SQLException e) {
                if (attempt == 49) throw e;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    /** {@code data_dir/jobs/<id>}. */
    public static Path jobDir(Path dataDir, String jobId) {
        return dataDir.resolve("jobs").resolve(jobId);
    }

    public Path jobDir(String jobId) {
        return jobDir(dataDir, jobId);
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new queued job at stage {@code upload}: a 12-hex-digit id, created now (UTC, seconds). */
    public synchronized Job create(List<String> inputs, ObjectNode options) {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        String createdAt = ZonedDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+00:00'"));
        Job job = new Job(HexFormat.of().formatHex(bytes), createdAt, Status.QUEUED, "upload",
                List.copyOf(inputs), options == null ? JsonNodeFactory.instance.objectNode() : options,
                null, null);
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO jobs VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)")) {
            ps.setString(1, job.id());
            ps.setString(2, job.createdAt());
            ps.setString(3, job.status().value());
            ps.setString(4, job.stage());
            ps.setString(5, dumps(MAPPER.valueToTree(job.inputs())));
            ps.setString(6, dumps(job.options()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return job;
    }

    public synchronized Job get(String jobId) {
        try (PreparedStatement ps = db.prepareStatement("SELECT * FROM jobs WHERE id = ?")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? row(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The newest jobs first. */
    public synchronized List<Job> list(int limit) {
        try (PreparedStatement ps =
                db.prepareStatement("SELECT * FROM jobs ORDER BY created_at DESC, rowid DESC LIMIT ?")) {
            ps.setInt(1, limit);
            List<Job> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sets the non-null fields only (as in Python, an error or report cannot be cleared). */
    public synchronized void update(String jobId, Status status, String stage, String error, ObjectNode report) {
        List<String> sets = new ArrayList<>();
        List<String> args = new ArrayList<>();
        if (status != null) { sets.add("status = ?"); args.add(status.value()); }
        if (stage != null) { sets.add("stage = ?"); args.add(stage); }
        if (error != null) { sets.add("error = ?"); args.add(error); }
        if (report != null) { sets.add("report = ?"); args.add(dumps(report)); }
        if (sets.isEmpty()) return;
        try (PreparedStatement ps =
                db.prepareStatement("UPDATE jobs SET " + String.join(", ", sets) + " WHERE id = ?")) {
            for (int i = 0; i < args.size(); i++) ps.setString(i + 1, args.get(i));
            ps.setString(args.size() + 1, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Marks the jobs a stopped server left queued or running as failed, so they do not show as
     * running for ever (the dispatcher runs in the server's process, so nothing else will finish
     * them). Returns how many there were.
     */
    public synchronized int failInterrupted() {
        try (PreparedStatement ps = db.prepareStatement("UPDATE jobs SET status = ?, error = ? WHERE status IN (?, ?)")) {
            ps.setString(1, Status.FAILED.value());
            ps.setString(2, "Lilyscan stopped while this job was running; re-run it.");
            ps.setString(3, Status.QUEUED.value());
            ps.setString(4, Status.RUNNING.value());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String dumps(JsonNode node) {
        return PyJson.write(node, PyJson.Style.JSON_DUMPS_COMPACT);
    }

    private static Job row(ResultSet rs) throws SQLException {
        try {
            List<String> inputs = new ArrayList<>();
            MAPPER.readTree(rs.getString("inputs")).forEach(n -> inputs.add(n.asText()));
            String report = rs.getString("report");
            return new Job(
                    rs.getString("id"),
                    rs.getString("created_at"),
                    Status.of(rs.getString("status")),
                    rs.getString("stage"),
                    inputs,
                    (ObjectNode) MAPPER.readTree(rs.getString("options")),
                    rs.getString("error"),
                    report == null || report.isEmpty() ? null : (ObjectNode) MAPPER.readTree(report));
        } catch (IOException e) {
            throw new IllegalStateException("corrupt job row", e);
        }
    }
}

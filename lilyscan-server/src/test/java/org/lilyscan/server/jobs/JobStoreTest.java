package org.lilyscan.server.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobStoreTest {
    @TempDir Path dir;

    @Test
    void createGetListUpdate() throws Exception {
        try (JobStore store = new JobStore(dir)) {
            var opts = JsonNodeFactory.instance.objectNode().put("straighten", true);
            JobStore.Job a = store.create(List.of("00-a.pdf"), opts);
            JobStore.Job b = store.create(List.of("00-b.png", "01-c.png"), null);
            assertTrue(a.id().matches("[0-9a-f]{12}"));
            assertTrue(a.createdAt().matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\+00:00"));
            assertEquals(JobStore.Status.QUEUED, a.status());
            assertEquals("upload", a.stage());
            assertEquals(List.of(b.id(), a.id()), store.list(100).stream().map(JobStore.Job::id).toList());

            var report = JsonNodeFactory.instance.objectNode().put("ir", "ir/score.json");
            store.update(a.id(), JobStore.Status.DONE, "done", null, report);
            JobStore.Job got = store.get(a.id());
            assertEquals(JobStore.Status.DONE, got.status());
            assertEquals("done", got.stage());
            assertNull(got.error());
            assertEquals(report, got.report());
            assertEquals(opts, got.options());
            assertNull(store.get("nope"));
            assertEquals(dir.resolve("jobs").resolve(a.id()), store.jobDir(a.id()));
        }
        // The encodings Python's json.dumps writes.
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("jobs.sqlite"));
                var rs = c.createStatement().executeQuery(
                        "SELECT inputs, options, report FROM jobs WHERE status = 'queued'")) {
            assertTrue(rs.next());
            assertEquals("[\"00-b.png\", \"01-c.png\"]", rs.getString(1));
            assertEquals("{}", rs.getString(2));
            assertNull(rs.getString(3));
        }
    }

    @Test
    void jobsInterruptedByAStopAreFailedOnTheNextStart(@TempDir Path other) {
        String queued, running, done;
        try (JobStore store = new JobStore(other)) {
            queued = store.create(List.of("00-a.pdf"), null).id();
            running = store.create(List.of("00-b.pdf"), null).id();
            done = store.create(List.of("00-c.pdf"), null).id();
            store.update(running, JobStore.Status.RUNNING, "engine", null, null);
            store.update(done, JobStore.Status.DONE, "done", null, null);
        }
        try (JobStore store = new JobStore(other)) {
            assertEquals(2, store.failInterrupted());
            for (String id : List.of(queued, running)) {
                assertEquals(JobStore.Status.FAILED, store.get(id).status());
                assertEquals("Lilyscan stopped while this job was running; re-run it.", store.get(id).error());
            }
            assertEquals("engine", store.get(running).stage());
            assertEquals(JobStore.Status.DONE, store.get(done).status());
            assertEquals(0, store.failInterrupted());
        }
    }
}

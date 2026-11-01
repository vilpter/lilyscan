package org.lilyscan.server.jobs;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One job at a time, in-process (the Python {@code InlineDispatcher}): each request runs its
 * steps in order on a single worker thread and stops at the first that fails. Steps record
 * their own failures on the job.
 */
public final class Dispatcher implements AutoCloseable {

    /** One step of a job (a task in {@code tasks.py}). */
    @FunctionalInterface
    public interface Step {
        void run(String jobId) throws Exception;
    }

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lilyscan-job");
        t.setDaemon(true);
        return t;
    });

    public void run(String jobId, List<Step> steps) {
        pool.submit(() -> {
            for (Step step : steps) {
                try {
                    step.run(jobId);
                } catch (Exception | Error e) {
                    log.error("step failed for job {}", jobId, e);
                    return;
                }
            }
        });
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}

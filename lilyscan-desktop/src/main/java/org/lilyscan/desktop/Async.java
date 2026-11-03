package org.lilyscan.desktop;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** Server calls off the event thread, their results back on it. */
final class Async {
    private Async() {}

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "lilyscan-api");
        t.setDaemon(true);
        return t;
    });

    @FunctionalInterface
    interface Call<T> {
        T call() throws Exception;
    }

    /** Runs {@code call} in the background, then {@code done} or {@code failed} on the event thread. */
    static <T> void run(Call<T> call, Consumer<T> done, Consumer<Exception> failed) {
        POOL.execute(() -> {
            try {
                T value = call.call();
                SwingUtilities.invokeLater(() -> done.accept(value));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> failed.accept(e));
            }
        });
    }

    static String message(Exception e) {
        String m = e.getMessage();
        if (e instanceof java.net.ConnectException || m == null || m.isBlank()) {
            return "the server does not answer (" + e.getClass().getSimpleName() + ")";
        }
        return m;
    }
}

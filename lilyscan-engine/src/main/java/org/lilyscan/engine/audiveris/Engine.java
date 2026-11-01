package org.lilyscan.engine.audiveris;

/**
 * One-time preparation of the JVM for running Audiveris in-process (D15, D16).
 *
 * <p>Audiveris was built as a desktop application. On Linux it probes GTK for HiDPI scaling in a
 * static initializer and crashes when GTK is absent, even in batch mode; fixing the scale skips
 * the probe (design.md §5). The engine never opens a window, so AWT runs headless. These must be
 * set before any AWT or Audiveris class initializes — the forked engine worker also passes them
 * on its command line.
 */
public final class Engine {
    private Engine() {}

    private static volatile boolean ready;

    /** Idempotent; call before touching Audiveris classes. */
    public static void init() {
        if (ready) {
            return;
        }
        synchronized (Engine.class) {
            if (ready) {
                return;
            }
            setIfAbsent("java.awt.headless", "true");
            setIfAbsent("sun.java2d.uiScale", "1");
            ready = true;
        }
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}

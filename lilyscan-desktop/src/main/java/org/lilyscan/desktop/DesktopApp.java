package org.lilyscan.desktop;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.nio.file.Path;
import java.util.prefs.Preferences;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * The optional Swing desktop front end (D14). It does what the web UI does (upload, follow,
 * review in three panes, edit and recompile, combine parts, re-run, download) over the Lilyscan
 * HTTP API: on this computer through the server started embedded on a private localhost port,
 * or against a Lilyscan server elsewhere.
 *
 * <pre>
 *   lilyscan desktop                      # this computer (jobs in the per-user data folder)
 *   lilyscan desktop --data DIR           # this computer, jobs in DIR
 *   lilyscan desktop --server URL         # a Lilyscan server
 * </pre>
 *
 * Without options the last choice is used again.
 */
public final class DesktopApp {
    private DesktopApp() {}

    static final Preferences PREFS = Preferences.userNodeForPackage(DesktopApp.class);
    static final String PREF_SERVER = "server";
    static final String PREF_REMOTE = "remote";

    public static void main(String[] args) {
        String server = null;
        Path data = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server" -> server = args[++i];
                case "--data" -> data = Path.of(args[++i]);
                case "--local" -> server = "";
                case "-h", "--help" -> {
                    System.out.println("usage: lilyscan desktop [--local | --server URL] [--data DIR]");
                    return;
                }
                default -> {
                    System.err.println("unknown option " + args[i]);
                    System.exit(64);
                }
            }
        }
        run(server, data);
    }

    /** {@code server}: a URL, {@code ""} for this computer, {@code null} for the last choice. */
    public static void run(String server, Path data) {
        System.setProperty("flatlaf.uiScale.allowScaleDown", "true");
        String chosen = server;
        if (chosen == null) chosen = PREFS.getBoolean(PREF_REMOTE, false) ? PREFS.get(PREF_SERVER, "") : "";
        String target = chosen;
        Path dataDir = data;
        SwingUtilities.invokeLater(() -> {
            if (Boolean.getBoolean("lilyscan.dark")) FlatDarkLaf.setup(); else FlatLightLaf.setup();
            MainWindow window = new MainWindow(dataDir);
            window.setVisible(true);
            window.connect(target.isEmpty() ? null : target, error -> {
                if (!target.isEmpty()) {
                    JOptionPane.showMessageDialog(window, "Could not connect to " + target + ": " + error
                            + "\nUsing this computer instead.", "Lilyscan", JOptionPane.WARNING_MESSAGE);
                    window.connect(null, e2 -> window.fatal(e2));
                } else {
                    window.fatal(error);
                }
            });
        });
    }
}

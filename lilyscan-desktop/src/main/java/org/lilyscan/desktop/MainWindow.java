package org.lilyscan.desktop;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Desktop;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

/** The window: the home page (upload, jobs, combine) or one job's review, as the web UI's two views. */
final class MainWindow extends JFrame {
    private final Path dataDir;
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JLabel status = new JLabel(" ");
    private final JMenuItem openData = new JMenuItem("Open the jobs folder");
    private Connection connection;
    private HomePanel home;
    private JobPanel job;

    MainWindow(Path dataDir) {
        super("Lilyscan");
        this.dataDir = dataDir;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                quit();
            }
        });
        setJMenuBar(menus());
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        status.setForeground(UIManager.getColor("Label.disabledForeground"));
        center.add(new JLabel("Starting...", JLabel.CENTER), "start");
        getContentPane().add(center, BorderLayout.CENTER);
        getContentPane().add(status, BorderLayout.SOUTH);
        setSize(1400, 900);
        setLocationByPlatform(true);
    }

    private JMenuBar menus() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.setMnemonic(KeyEvent.VK_F);
        JMenuItem homeItem = new JMenuItem("Home");
        homeItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, KeyEvent.CTRL_DOWN_MASK));
        homeItem.addActionListener(e -> showHome());
        JMenuItem upload = new JMenuItem("Transcribe files...");
        upload.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK));
        upload.addActionListener(e -> {
            if (home != null && leaveJob()) {
                showHome();
                home.chooseFiles();
            }
        });
        JMenuItem quit = new JMenuItem("Quit");
        quit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, KeyEvent.CTRL_DOWN_MASK));
        quit.addActionListener(e -> quit());
        file.add(homeItem);
        file.add(upload);
        file.addSeparator();
        file.add(quit);

        JMenu conn = new JMenu("Connection");
        conn.setMnemonic(KeyEvent.VK_C);
        JMenuItem local = new JMenuItem("This computer");
        local.addActionListener(e -> switchTo(null));
        JMenuItem remote = new JMenuItem("Lilyscan server...");
        remote.addActionListener(e -> {
            String url = (String) JOptionPane.showInputDialog(this,
                    "Address of a Lilyscan server (as for the web UI):", "Connect to a server",
                    JOptionPane.PLAIN_MESSAGE, null, null, DesktopApp.PREFS.get(DesktopApp.PREF_SERVER, "http://localhost:8000"));
            if (url != null && !url.isBlank()) switchTo(url.strip());
        });
        openData.addActionListener(e -> {
            if (connection instanceof Connection.Local l) {
                try {
                    Desktop.getDesktop().open(l.dataDir().toFile());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Jobs are kept in " + l.dataDir(), "Lilyscan",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }
        });
        conn.add(local);
        conn.add(remote);
        conn.addSeparator();
        conn.add(openData);
        bar.add(file);
        bar.add(conn);
        return bar;
    }

    /** Connects to {@code url}, or to this computer when {@code url} is null. */
    void connect(String url, Consumer<String> failed) {
        status.setText(url == null ? "Starting the Lilyscan engine on this computer..." : "Connecting to " + url + "...");
        Async.run(() -> url == null ? Connection.local(dataDir) : Connection.remote(url), c -> {
            DesktopApp.PREFS.putBoolean(DesktopApp.PREF_REMOTE, url != null);
            if (url != null) DesktopApp.PREFS.put(DesktopApp.PREF_SERVER, url);
            attach(c);
        }, e -> failed.accept(Async.message(e)));
    }

    /** Shows the jobs of connection {@code c}, closing the previous one. */
    void attach(Connection c) {
        if (connection != null && connection != c) connection.close();
        connection = c;
        openData.setEnabled(c instanceof Connection.Local);
        setTitle("Lilyscan — " + c.label());
        if (home != null) center.remove(home);
        if (job != null) center.remove(job);
        home = new HomePanel(c.api(), this::showJob);
        job = new JobPanel(c.api(), this::showJob, this::showHome);
        center.add(home, "home");
        center.add(job, "job");
        showHome();
        Async.run(() -> c.api().health(), h -> status.setText(c.label() + " · LilyPond "
                + h.path("lilypond").asText() + " · Audiveris " + h.path("audiveris").asText()
                + (c instanceof Connection.Local l ? " · jobs in " + l.dataDir() : "")), e -> { });
    }

    private static long running(Api api) {
        try {
            long n = 0;
            for (var j : api.jobs()) if (Labels.busy(j)) n++;
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    HomePanel home() {
        return home;
    }

    JobPanel job() {
        return job;
    }

    private void switchTo(String url) {
        if (!leaveJob()) return;
        connect(url, error -> JOptionPane.showMessageDialog(this, "Could not connect: " + error, "Lilyscan",
                JOptionPane.ERROR_MESSAGE));
    }

    void fatal(String error) {
        JOptionPane.showMessageDialog(this, "Lilyscan could not start: " + error, "Lilyscan", JOptionPane.ERROR_MESSAGE);
        status.setText("Not connected: " + error);
    }

    /** Unsaved edits: ask before leaving the job. */
    private boolean leaveJob() {
        if (job == null || !job.isShowing() || !job.dirty()) return true;
        return JOptionPane.showConfirmDialog(this, "Your edits to the LilyPond source are not saved. Leave anyway?",
                "Unsaved edits", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    void showHome() {
        if (home == null || !leaveJob()) return;
        if (job != null) job.stop();
        cards.show(center, "home");
        home.start();
    }

    void showJob(String id) {
        if (job == null) return;
        if (job.isShowing() && !id.equals(job.jobId()) && !leaveJob()) return;
        home.stop();
        cards.show(center, "job");
        job.open(id);
    }

    private void quit() {
        if (!leaveJob()) return;
        if (connection instanceof Connection.Local l && running(l.api()) > 0
                && JOptionPane.showConfirmDialog(this, "Jobs are running on this computer. Quitting stops them"
                        + " (they can be re-run later). Quit anyway?", "Jobs running", JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        if (connection != null) connection.close();
        dispose();
        System.exit(0);
    }
}

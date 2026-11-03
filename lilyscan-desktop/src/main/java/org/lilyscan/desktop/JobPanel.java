package org.lilyscan.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

/**
 * One job (services/web/index.html #job): its progress, then the three-pane review. Source,
 * Engraved and LilyPond panes and the review list share one selected measure: clicking a
 * measure anywhere, or putting the caret on its line, highlights it in the others.
 */
final class JobPanel extends JPanel {
    private static final String PREPARE = "Prepare pages for the engine (render PDFs sharply; straighten photos and scans)";
    private static final String[][] DOWNLOADS = {
        {"ly", ".ly", "One LilyPond file: the score and, when there are several, each part"},
        {"ly-project", ".ly project", "The editable project: one file per part, as in the editor (zip)"},
        {"pdf", "PDF", "The engraved score"},
        {"midi", "MIDI", "The score as MIDI"},
        {"musicxml", "MusicXML", "The score as transcribed and repaired (edits to the LilyPond source are not in it)"},
        {"omr", ".omr", "The Audiveris project, to open in the Audiveris application"},
    };

    private final Api api;
    private final Consumer<String> openJob;
    private final Timer poll = new Timer(2000, e -> pollJob());

    // State, as app.js keeps it.
    private String id;
    private JsonNode job;
    private JsonNode review;
    private final Map<String, JsonNode> byId = new HashMap<>();
    private final Map<String, String> byLine = new HashMap<>();
    private final Map<String, String> files = new LinkedHashMap<>();
    private final Set<String> dirty = new LinkedHashSet<>();
    private String current;
    private String selected;
    private boolean loading; // programmatic editor changes
    private int generation; // drops answers for a job no longer shown

    // Header
    private final JLabel title = new JLabel();
    private final JLabel status = Ui.pill("", Ui.UNKNOWN);
    private final JPanel badges = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel downloads = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JButton rerunBtn = new JButton("Re-run...");
    private final JLabel error = new JLabel();
    private final JPanel notes = new JPanel();
    private final JPanel wait = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
    private final JLabel waitText = new JLabel("Working...");

    // Panes
    private final JPanel panes = new JPanel(new BorderLayout());
    private final SourcePane source = new SourcePane(this::select);
    private final ScorePane score = new ScorePane(this::select);
    private final JScrollPane sourceScroll = new JScrollPane(source);
    private final JScrollPane scoreScroll = new JScrollPane(score);
    private final RSyntaxTextArea code = new RSyntaxTextArea();
    private final JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    private final JButton saveBtn = new JButton("Save & recompile");
    private final javax.swing.JTextArea selection = new javax.swing.JTextArea(2, 20);
    private final JPanel repairs = new JPanel();
    private final JPanel lyricsCheck = new JPanel();
    private final JLabel reviewCount = Ui.muted("");
    private final DefaultListModel<String> reviewModel = new DefaultListModel<>();
    private final JList<String> reviewList = new JList<>(reviewModel);
    private boolean listUpdating;
    private Object lineHighlight; // the selected measure's line in the editor

    JobPanel(Api api, Consumer<String> openJob, Runnable home) {
        super(new BorderLayout());
        this.api = api;
        this.openJob = openJob;
        poll.setRepeats(false);
        setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        // Header bar
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        JButton back = new JButton("← Jobs");
        back.addActionListener(e -> home.run());
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() * 1.15f));
        bar.add(back);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(title);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(status);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(badges);
        bar.add(Box.createHorizontalGlue());
        bar.add(downloads);
        bar.add(Box.createHorizontalStrut(6));
        rerunBtn.setToolTipText("Transcribe the same pages again with other settings");
        rerunBtn.addActionListener(e -> rerun());
        bar.add(rerunBtn);
        for (String[] d : DOWNLOADS) {
            JButton b = new JButton(d[1]);
            b.setToolTipText(d[2]);
            b.putClientProperty("JButton.buttonType", "borderless");
            b.addActionListener(e -> download(d[0]));
            downloads.add(b);
        }

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(Ui.left(bar));
        error.setForeground(Ui.LOW);
        error.setVisible(false);
        top.add(Ui.left(error));
        notes.setLayout(new BoxLayout(notes, BoxLayout.Y_AXIS));
        notes.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0), Ui.card()));
        notes.setVisible(false);
        top.add(Ui.left(notes));
        JProgressBar spin = new JProgressBar();
        spin.setIndeterminate(true);
        spin.setPreferredSize(new Dimension(120, 8));
        wait.add(spin);
        wait.add(waitText);
        wait.setVisible(false);
        top.add(Ui.left(wait));
        add(top, BorderLayout.NORTH);

        // Panes
        sourceScroll.getVerticalScrollBar().setUnitIncrement(24);
        scoreScroll.getVerticalScrollBar().setUnitIncrement(24);
        sourceScroll.getViewport().setBackground(Color.WHITE);
        scoreScroll.getViewport().setBackground(Color.WHITE);
        JPanel sourcePanel = pane(withZoom(legend(), source::zoom, sourceScroll), sourceScroll);
        JPanel scorePanel = pane(withZoom(Ui.heading("Engraved"), score::zoom, scoreScroll), scoreScroll);

        code.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        code.setCodeFoldingEnabled(false);
        code.setAntiAliasingEnabled(true);
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        code.setTabSize(2);
        code.setHighlightCurrentLine(true);
        code.setEditable(true);
        code.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                edited();
            }

            public void removeUpdate(DocumentEvent e) {
                edited();
            }

            public void changedUpdate(DocumentEvent e) {}
        });
        code.addCaretListener(e -> caretMoved());
        RTextScrollPane codeScroll = new RTextScrollPane(code, true);
        selection.setEditable(false);
        selection.setLineWrap(true);
        selection.setWrapStyleWord(true);
        selection.setOpaque(false);
        selection.setFocusable(false);
        selection.setFont(javax.swing.UIManager.getFont("Label.font"));
        selection.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        saveBtn.setEnabled(false);
        saveBtn.setToolTipText("Save all edited files and recompile");
        saveBtn.addActionListener(e -> save());
        JPanel editorHead = new JPanel(new BorderLayout());
        editorHead.add(Ui.heading("LilyPond"), BorderLayout.WEST);
        editorHead.add(saveBtn, BorderLayout.EAST);
        JPanel editorTop = new JPanel(new BorderLayout(0, 4));
        editorTop.add(editorHead, BorderLayout.NORTH);
        editorTop.add(tabs, BorderLayout.SOUTH);
        JPanel editor = new JPanel(new BorderLayout(0, 4));
        editor.add(editorTop, BorderLayout.NORTH);
        editor.add(codeScroll, BorderLayout.CENTER);
        editor.add(selection, BorderLayout.SOUTH);

        reviewList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        reviewList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                JsonNode m = byId.get((String) value);
                String text = m == null ? String.valueOf(value) : reviewText(m);
                super.getListCellRendererComponent(list, "<html><b>m. " + Ui.escape(m == null ? "" : m.path("number").asText())
                        + "</b>" + Ui.escape(text) + "</html>", index, sel, focus);
                if (m != null) {
                    List<String> details = new ArrayList<>();
                    m.path("issues").forEach(i -> details.add(i.path("detail").asText()));
                    setToolTipText(String.join("\n", details));
                }
                return this;
            }
        });
        reviewList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !listUpdating && reviewList.getSelectedValue() != null) {
                select(reviewList.getSelectedValue(), "review");
            }
        });
        repairs.setLayout(new BoxLayout(repairs, BoxLayout.Y_AXIS));
        repairs.setVisible(false);
        lyricsCheck.setLayout(new BoxLayout(lyricsCheck, BoxLayout.Y_AXIS));
        lyricsCheck.setBorder(Ui.card());
        lyricsCheck.setVisible(false);
        JPanel reviewHead = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        reviewHead.add(Ui.heading("Review"));
        reviewHead.add(reviewCount);
        JPanel extras = new JPanel();
        extras.setLayout(new BoxLayout(extras, BoxLayout.Y_AXIS));
        extras.add(Ui.left(repairs));
        extras.add(Ui.left(lyricsCheck));
        extras.add(Ui.left(reviewHead));
        JPanel reviewPanel = new JPanel(new BorderLayout(0, 4));
        reviewPanel.add(extras, BorderLayout.NORTH);
        reviewPanel.add(new JScrollPane(reviewList), BorderLayout.CENTER);

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editor, reviewPanel);
        right.setResizeWeight(0.65);
        JSplitPane middle = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scorePanel, right);
        middle.setResizeWeight(0.5);
        JSplitPane all = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourcePanel, middle);
        all.setResizeWeight(0.33);
        panes.add(all, BorderLayout.CENTER);
        panes.setVisible(false);
        add(panes, BorderLayout.CENTER);
    }

    private static JPanel pane(Component head, Component body) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 2));
        p.add(head, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    /** A pane heading with zoom buttons; Ctrl + mouse wheel zooms too. */
    private static JPanel withZoom(Component head, java.util.function.DoubleUnaryOperator zoom, JScrollPane scroll) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(head, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        String[][] specs = {{"−", "Zoom out", "0.8"}, {"Fit", "Fit the width", "0"}, {"+", "Zoom in", "1.25"}};
        for (String[] b : specs) {
            JButton button = new JButton(b[0]);
            button.setToolTipText(b[1]);
            button.putClientProperty("JButton.buttonType", "toolBarButton");
            double f = Double.parseDouble(b[2]);
            button.addActionListener(e -> {
                zoom.applyAsDouble(f);
                scroll.revalidate();
                scroll.repaint();
            });
            buttons.add(button);
        }
        p.add(buttons, BorderLayout.EAST);
        Component view = scroll.getViewport().getView();
        view.addMouseWheelListener(e -> {
            if (!e.isControlDown()) {
                // Not for us: let the scroll pane scroll.
                scroll.dispatchEvent(javax.swing.SwingUtilities.convertMouseEvent(view, e, scroll));
                return;
            }
            e.consume();
            zoom.applyAsDouble(e.getWheelRotation() < 0 ? 1.1 : 1 / 1.1);
            scroll.revalidate();
            scroll.repaint();
        });
        scroll.setWheelScrollingEnabled(true);
        return p;
    }

    private static JPanel legend() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.add(Ui.heading("Source"));
        p.add(Ui.pill("≥0.8", Ui.HIGH));
        p.add(Ui.pill("0.5-0.8", Ui.MID));
        p.add(Ui.pill("<0.5", Ui.LOW));
        p.add(Ui.pill("needs review", Ui.ISSUE));
        return p;
    }

    // For tests: what the panes show.
    String selectedMeasure() {
        return selected;
    }

    String currentFile() {
        return current;
    }

    RSyntaxTextArea editor() {
        return code;
    }

    ScorePane score() {
        return score;
    }

    JList<String> reviewList() {
        return reviewList;
    }

    JButton saveButton() {
        return saveBtn;
    }

    /** The review, the engraved pages and the source are all loaded. */
    boolean loaded() {
        return review != null && !files.isEmpty() && !score.linkedMeasures().isEmpty();
    }

    JsonNode measure(String mid) {
        return byId.get(mid);
    }

    String jobId() {
        return id;
    }

    boolean dirty() {
        return !dirty.isEmpty();
    }

    void stop() {
        poll.stop();
    }

    void open(String jobId) {
        poll.stop();
        if (!jobId.equals(id)) {
            generation++;
            id = jobId;
            job = null;
            review = null;
            files.clear();
            dirty.clear();
            current = null;
            selected = null;
            panes.setVisible(false);
            downloads.setVisible(false);
            badges.removeAll();
            title.setText(" ");
            notes.setVisible(false);
        }
        pollJob();
    }

    // --- progress ---------------------------------------------------------------------------

    private void pollJob() {
        int gen = generation;
        String jobId = id;
        Async.run(() -> api.job(jobId), j -> {
            if (gen != generation) return;
            job = j;
            title.setText(Labels.jobLabel(j));
            rerunBtn.setVisible(!j.path("inputs").isEmpty());
            String st = j.path("status").asText();
            Ui.pill(status, st, Ui.status(st));
            boolean busy = Labels.busy(j);
            wait.setVisible(busy);
            waitText.setText(Labels.stage(j.path("stage").asText()));
            saveBtn.setEnabled(!busy && !dirty.isEmpty());
            showError(st.equals("failed") ? j.path("error").asText("failed") : null);
            if (busy) {
                if (isShowing()) poll.restart();
                return;
            }
            if (!j.path("report").isNull() && !j.path("report").isMissingNode()) loadReview(gen);
        }, e -> {
            if (gen == generation) showError(Async.message(e));
        });
    }

    private void showError(String message) {
        error.setVisible(message != null);
        error.setText(message == null ? "" : Ui.wrap(message, 900));
    }

    private void loadReview(int gen) {
        String jobId = id;
        Async.run(() -> api.review(jobId), r -> {
            if (gen != generation) return;
            review = r;
            byId.clear();
            byLine.clear();
            for (JsonNode m : r.path("measures")) {
                byId.put(m.path("id").asText(), m);
                if (m.path("file").isTextual()) byLine.put(m.path("file").asText() + ":" + m.path("line").asInt(), m.path("id").asText());
            }
            panes.setVisible(true);
            downloads.setVisible(true);
            renderBadges(r.path("qa"));
            source.show(r);
            renderNotes(r);
            renderRepairs(r);
            renderLyricsCheck(r);
            renderReviewList(r);
            loadPages(gen, r);
            loadScore(gen, r);
            loadFiles(gen, r);
            revalidate();
            repaint();
        }, e -> {
            if (gen == generation) showError("No review available for this job (" + Async.message(e) + ").");
        });
    }

    private void renderBadges(JsonNode qa) {
        badges.removeAll();
        for (JsonNode c : qa.path("checks")) {
            boolean passed = c.path("passed").asBoolean();
            JLabel b = Ui.pill(c.path("id").asText(), passed ? Ui.HIGH : Ui.LOW);
            b.setToolTipText(c.path("name").asText() + ": " + c.path("summary").asText());
            badges.add(b);
        }
        badges.revalidate();
    }

    private void loadPages(int gen, JsonNode r) {
        String jobId = id;
        for (int i = 0; i < source.pageCount(); i++) {
            int index = i;
            Async.run(() -> ImageIO.read(new java.io.ByteArrayInputStream(api.page(jobId, index + 1))), img -> {
                if (gen == generation) source.setImage(index, img);
            }, e -> { });
        }
    }

    private void loadScore(int gen, JsonNode r) {
        String jobId = id;
        List<String> svgs = new ArrayList<>();
        r.path("lilypond").path("svg").forEach(p -> svgs.add(p.asText()));
        Map<String, String> lines = Map.copyOf(byLine);
        Async.run(() -> {
            List<ScorePane.Page> pages = new ArrayList<>();
            for (String p : svgs) pages.add(ScorePane.Page.load(api.file(jobId, p)));
            return pages;
        }, pages -> {
            if (gen != generation) return;
            score.show(pages, lines);
            if (selected != null) score.setSelected(selected, false);
        }, e -> {
            if (gen == generation) score.clear("The engraved pages could not be shown: " + Async.message(e));
        });
    }

    // --- editor ------------------------------------------------------------------------------

    private void loadFiles(int gen, JsonNode r) {
        String jobId = id;
        List<String> names = new ArrayList<>();
        r.path("lilypond").path("files").forEach(f -> {
            String n = f.asText().replaceFirst("^ly/", "");
            if (n.endsWith(".ly")) names.add(n);
        });
        names.sort(Comparator.comparingInt(Labels::fileOrder).thenComparing(Comparator.naturalOrder()));
        Async.run(() -> {
            Map<String, String> texts = new LinkedHashMap<>();
            for (String n : names) texts.put(n, api.ly(jobId, n));
            return texts;
        }, texts -> {
            if (gen != generation) return;
            files.clear();
            files.putAll(texts);
            dirty.clear();
            if (current == null || !files.containsKey(current)) current = names.isEmpty() ? null : names.get(0);
            showFile(current);
            if (selected != null && byId.containsKey(selected)) select(selected, null);
        }, e -> {
            if (gen == generation) showError("The LilyPond source could not be loaded: " + Async.message(e));
        });
    }

    private void renderTabs() {
        tabs.removeAll();
        ButtonGroup group = new ButtonGroup();
        for (String f : files.keySet()) {
            JToggleButton b = new JToggleButton(Labels.tab(f) + (dirty.contains(f) ? " •" : ""), f.equals(current));
            b.setToolTipText(f);
            b.putClientProperty("JButton.buttonType", "tab");
            b.addActionListener(e -> showFile(f));
            group.add(b);
            tabs.add(b);
        }
        tabs.revalidate();
        tabs.repaint();
        saveBtn.setEnabled(!dirty.isEmpty() && (job == null || !Labels.busy(job)));
    }

    private void showFile(String name) {
        current = name;
        if (lineHighlight != null) {
            code.removeLineHighlight(lineHighlight);
            lineHighlight = null;
        }
        loading = true;
        code.setText(name == null ? "" : files.get(name));
        code.setCaretPosition(0);
        code.discardAllEdits();
        loading = false;
        renderTabs();
    }

    private void edited() {
        if (loading || current == null) return;
        files.put(current, code.getText());
        if (dirty.add(current)) renderTabs();
        saveBtn.setEnabled(job == null || !Labels.busy(job));
    }

    private void caretMoved() {
        if (loading || current == null) return;
        int line;
        try {
            line = code.getLineOfOffset(code.getCaretPosition()) + 1;
        } catch (BadLocationException e) {
            return;
        }
        // The measure whose music line is at or above the caret.
        for (int n = line; n > 0; n--) {
            String mid = byLine.get(current + ":" + n);
            if (mid != null) {
                if (!mid.equals(selected)) select(mid, "editor");
                return;
            }
        }
    }

    private void revealLine(int line) {
        try {
            int start = code.getLineStartOffset(line - 1);
            int end = code.getLineEndOffset(line - 1);
            String text = code.getText(start, end - start);
            int len = text.endsWith("\n") ? text.length() - 1 : text.length();
            if (lineHighlight != null) code.removeLineHighlight(lineHighlight);
            lineHighlight = code.addLineHighlight(line - 1, Ui.SEL_FILL);
            loading = true;
            // The caret at the line's start (so the view does not scroll sideways), the line selected.
            code.setCaretPosition(start + len);
            code.moveCaretPosition(start);
            loading = false;
            java.awt.Rectangle r = code.modelToView2D(code.getLineStartOffset(Math.max(0, line - 5))).getBounds();
            java.awt.Rectangle view = code.getVisibleRect();
            code.scrollRectToVisible(new java.awt.Rectangle(0, r.y, view.width, view.height));
        } catch (BadLocationException e) {
            // the measure map is older than the file: nothing to show
        }
    }

    private void save() {
        saveBtn.setEnabled(false);
        String jobId = id;
        Map<String, String> edits = new LinkedHashMap<>();
        for (String f : dirty) edits.put(f, files.get(f));
        Async.run(() -> {
            for (var e : edits.entrySet()) api.putLy(jobId, e.getKey(), e.getValue());
            return api.recompile(jobId);
        }, j -> {
            dirty.removeAll(edits.keySet());
            renderTabs();
            pollJob();
        }, e -> {
            showError("Saving failed: " + Async.message(e));
            saveBtn.setEnabled(true);
        });
    }

    // --- notes, repairs, lyrics, review list -----------------------------------------------

    private void renderNotes(JsonNode r) {
        List<String> lines = new ArrayList<>();
        for (JsonNode page : r.path("prepare")) {
            String where = page.path("page").isInt() && page.path("page").asInt() > 0
                    ? page.path("input").asText() + ", page " + (page.path("page").asInt() + 1) : page.path("input").asText();
            if (page.path("error").isTextual() && !page.path("error").asText().isEmpty()) {
                lines.add(where + ": could not be read (" + page.path("error").asText() + "); it was left out.");
            }
            page.path("warnings").forEach(w -> lines.add(where + ": " + w.asText() + "."));
        }
        for (JsonNode a : r.path("alternatives")) {
            if (a.path("chosen").asBoolean() && a.path("pages").asText().equals("uploaded")) {
                lines.add("The pages as uploaded read better than the straightened ones, so those were kept.");
                break;
            }
        }
        notes.removeAll();
        notes.add(Ui.left(new JLabel("<html><b>Input quality</b></html>")));
        for (String l : lines) notes.add(Ui.left(new JLabel("• " + l)));
        notes.setVisible(!lines.isEmpty());
        notes.revalidate();
    }

    private void renderRepairs(JsonNode r) {
        repairs.removeAll();
        JsonNode list = r.path("repairs");
        repairs.add(Ui.left(Ui.muted("<html><b>Repaired automatically (" + list.size() + ")</b></html>")));
        for (JsonNode x : list) {
            repairs.add(Ui.left(Ui.muted(Ui.wrap(x.path("rule").asText() + " · " + x.path("detail").asText(), 420))));
        }
        repairs.setVisible(!list.isEmpty());
        repairs.revalidate();
    }

    private void renderLyricsCheck(JsonNode r) {
        lyricsCheck.removeAll();
        JsonNode found = null;
        for (JsonNode c : r.path("qa").path("checks")) {
            if (c.path("id").asText().equals("Q9")) found = c.path("details");
        }
        boolean any = found != null && !found.isEmpty();
        if (any) {
            lyricsCheck.add(Ui.left(new JLabel("<html><b>Lyrics in instrumental parts</b></html>")));
            lyricsCheck.add(Ui.left(new JLabel("Instruments rarely have words: these may be text read as lyrics.")));
            for (JsonNode d : found) {
                List<String> ms = new ArrayList<>();
                d.path("measures").forEach(m -> ms.add(m.asText()));
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
                row.add(new JLabel(d.path("name").asText() + ": " + d.path("events").asInt() + " syllable(s), m. "
                        + Labels.measures(ms)));
                JButton drop = new JButton("Drop these lyrics");
                String part = d.path("part").asText();
                drop.addActionListener(e -> dropLyrics(part));
                row.add(drop);
                lyricsCheck.add(Ui.left(row));
            }
        }
        lyricsCheck.setVisible(any);
        lyricsCheck.revalidate();
    }

    private void dropLyrics(String part) {
        if (!dirty.isEmpty()) {
            showError("Save your edits first: dropping lyrics edits the source and recompiles.");
            return;
        }
        String jobId = id;
        Async.run(() -> api.dropLyrics(jobId, part), j -> pollJob(),
                e -> showError("Dropping the lyrics failed: " + Async.message(e)));
    }

    private static String reviewText(JsonNode m) {
        Set<String> kinds = new LinkedHashSet<>();
        m.path("issues").forEach(i -> kinds.add(Labels.issue(i.path("kind").asText())));
        int staff = m.path("staff").asInt(1);
        return " · " + m.path("part_name").asText() + (staff > 1 ? " (staff " + staff + ")" : "") + " · "
                + String.join(", ", kinds);
    }

    private void renderReviewList(JsonNode r) {
        listUpdating = true;
        reviewModel.clear();
        r.path("review").forEach(x -> reviewModel.addElement(x.asText()));
        listUpdating = false;
        reviewCount.setText(reviewModel.size() + " measure(s)");
    }

    // --- selection ---------------------------------------------------------------------------

    /** Selects measure {@code mid} in every pane; {@code origin} is the pane it came from. */
    void select(String mid, String origin) {
        JsonNode m = byId.get(mid);
        if (m == null) return;
        selected = mid;
        source.setSelected(mid, !"source".equals(origin));
        score.setSelected(mid, !"score".equals(origin));
        listUpdating = true;
        int index = reviewModel.indexOf(mid);
        if (index >= 0) {
            reviewList.setSelectedIndex(index);
            if (!"review".equals(origin)) reviewList.ensureIndexIsVisible(index);
        } else {
            reviewList.clearSelection();
        }
        listUpdating = false;
        if (!"editor".equals(origin) && m.path("file").isTextual() && files.containsKey(m.path("file").asText())) {
            String f = m.path("file").asText();
            if (!f.equals(current)) showFile(f);
            int line = m.path("line").asInt();
            SwingUtilities.invokeLater(() -> revealLine(line));
        }
        selection.setText(describe(m));
    }

    /** The selection line: part, measure, lowest confidence, problems and Lilyscan's changes. */
    static String describe(JsonNode m) {
        List<String> problems = new ArrayList<>();
        m.path("issues").forEach(i -> problems.add(Labels.issue(i.path("kind").asText()) + ": " + i.path("detail").asText()));
        JsonNode conf = m.get("min_confidence");
        String confidence = conf == null || conf.isNull() ? "" : " · lowest confidence " + conf.asText();
        // Group the same change on several notes: "rhythm (was eighth) at beats 1, 1.33, 1.67".
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (JsonNode r : m.path("repairs")) {
            String detail = r.path("detail").isTextual() && !r.path("detail").asText().isEmpty() ? " (" + r.path("detail").asText() + ")" : "";
            grouped.computeIfAbsent(r.path("rule").asText() + detail, k -> new ArrayList<>()).add(Labels.beat(r.path("offset").asText()));
        }
        List<String> fixes = new ArrayList<>();
        grouped.forEach((k, beats) -> fixes.add(k + " at beat" + (beats.size() > 1 ? "s" : "") + " " + String.join(", ", beats)));
        int staff = m.path("staff").asInt(1);
        return m.path("part_name").asText() + (staff > 1 ? " staff " + staff : "") + ", measure " + m.path("number").asText()
                + confidence + (problems.isEmpty() ? "" : " · " + String.join(" · ", problems))
                + (fixes.isEmpty() ? "" : " · changed by Lilyscan: " + String.join("; ", fixes));
    }

    // --- downloads and re-run -------------------------------------------------------------------

    private void download(String kind) {
        String jobId = id;
        Async.run(() -> api.download(jobId, kind), d -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File(d.filename()));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File target = fc.getSelectedFile();
            if (target.exists() && JOptionPane.showConfirmDialog(this, target.getName() + " exists. Replace it?",
                    "Save", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            try {
                Files.write(target.toPath(), d.content());
            } catch (Exception e) {
                showError("Could not save " + target + ": " + e.getMessage());
            }
        }, e -> showError("Download failed: " + Async.message(e)));
    }

    private void rerun() {
        JsonNode options = job == null ? null : job.path("options");
        JTextField ocr = new JTextField(options != null && options.path("ocr_languages").isTextual()
                ? options.path("ocr_languages").asText() : "", 18);
        ocr.putClientProperty("JTextField.placeholderText", "keep this job's");
        JCheckBox straighten = new JCheckBox(PREPARE, options == null || options.path("prepare").asBoolean(true));
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(Ui.left(new JLabel("OCR languages for lyrics and text")));
        p.add(Ui.left(ocr));
        p.add(Box.createVerticalStrut(6));
        p.add(Ui.left(straighten));
        int answer = JOptionPane.showOptionDialog(this, p, "Re-run: the same pages, other settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null, new Object[] {"Start new job", "Cancel"},
                "Start new job");
        if (answer != 0) return;
        String jobId = id;
        Async.run(() -> api.rerun(jobId, ocr.getText(), straighten.isSelected()), j -> openJob.accept(j.path("id").asText()),
                e -> showError("Could not start: " + Async.message(e)));
    }
}

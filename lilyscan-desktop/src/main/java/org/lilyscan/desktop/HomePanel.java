package org.lilyscan.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** The home page: a new scan, combine parts, and the job list (services/web/index.html #home). */
final class HomePanel extends JPanel {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREPARE = "Prepare pages for the engine (render PDFs sharply; straighten photos and scans)";

    private final Api api;
    private final Consumer<String> openJob;
    private final Timer poll = new Timer(3000, e -> refresh());

    // New scan
    private final List<Path> chosen = new ArrayList<>();
    private final JLabel chosenLabel = Ui.muted(" ");
    private final JTextField ocr = new JTextField(16);
    private final JCheckBox straighten = new JCheckBox(PREPARE, true);
    private final JButton uploadBtn = new JButton("Transcribe");
    private final JLabel uploadMsg = Ui.muted(" ");

    // Jobs
    private final DefaultTableModel jobsModel = new DefaultTableModel(new Object[] {"Job", "Input", "Status", "Created"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable jobsTable = new JTable(jobsModel);
    private final List<JsonNode> jobs = new ArrayList<>();

    // Combine
    private final JPanel combineRows = new JPanel();
    private final JTextField combineTitle = new JTextField(20);
    private final JButton combineBtn = new JButton("Combine");
    private final JLabel combineMsg = Ui.muted(" ");
    private final Map<String, JsonNode> partsCache = new HashMap<>();
    private final List<PartRow> partRows = new ArrayList<>();
    private String combineKey = "";

    HomePanel(Api api, Consumer<String> openJob) {
        super(new BorderLayout());
        this.api = api;
        this.openJob = openJob;
        poll.setRepeats(false);

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(Ui.left(Ui.fixedHeight(uploadCard())));
        left.add(Box.createVerticalStrut(12));
        left.add(Ui.left(Ui.fixedHeight(combineCard())));
        left.add(Box.createVerticalGlue());

        JPanel jobsCard = new JPanel(new BorderLayout(0, 8));
        jobsCard.setBorder(Ui.card());
        jobsCard.add(Ui.heading("Jobs"), BorderLayout.NORTH);
        jobsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        jobsTable.setRowHeight(jobsTable.getRowHeight() + 6);
        jobsTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        jobsTable.getColumnModel().getColumn(1).setPreferredWidth(320);
        jobsTable.getColumnModel().getColumn(2).setPreferredWidth(150);
        jobsTable.getColumnModel().getColumn(3).setPreferredWidth(170);
        jobsTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                if (!sel && row < jobs.size()) setForeground(Ui.status(jobs.get(row).path("status").asText()));
                return this;
            }
        });
        jobsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = jobsTable.rowAtPoint(e.getPoint());
                if (row >= 0 && row < jobs.size()) openJob.accept(jobs.get(row).path("id").asText());
            }
        });
        jobsTable.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "open");
        jobsTable.getActionMap().put("open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int row = jobsTable.getSelectedRow();
                if (row >= 0 && row < jobs.size()) openJob.accept(jobs.get(row).path("id").asText());
            }
        });
        jobsCard.add(new JScrollPane(jobsTable), BorderLayout.CENTER);

        JScrollPane leftScroll = new JScrollPane(left);
        leftScroll.setBorder(BorderFactory.createEmptyBorder());
        leftScroll.getVerticalScrollBar().setUnitIncrement(16);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, jobsCard);
        split.setResizeWeight(0.45);
        split.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(split, BorderLayout.CENTER);
        setTransferHandler(dropHandler());
    }

    // --- new scan --------------------------------------------------------------------------

    private JPanel uploadCard() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(Ui.card());
        card.add(Ui.left(Ui.heading("New scan")));
        card.add(Box.createVerticalStrut(8));
        JButton choose = new JButton("Choose files...");
        choose.addActionListener(e -> chooseFiles());
        JLabel drop = new JLabel("<html><div style='text-align:center'>Drop a PDF or page images here,<br>or choose them.</div></html>",
                SwingConstants.CENTER);
        JPanel dropZone = new JPanel(new BorderLayout(0, 6));
        dropZone.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createDashedBorder(Ui.UNKNOWN, 4, 3),
                BorderFactory.createEmptyBorder(18, 12, 18, 12)));
        dropZone.add(drop, BorderLayout.CENTER);
        JPanel chooseRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        chooseRow.setOpaque(false);
        chooseRow.add(choose);
        dropZone.add(chooseRow, BorderLayout.SOUTH);
        dropZone.setTransferHandler(dropHandler());
        dropZone.setMaximumSize(new Dimension(Integer.MAX_VALUE, 140));
        card.add(Ui.left(dropZone));
        card.add(Box.createVerticalStrut(4));
        card.add(Ui.left(chosenLabel));
        card.add(Box.createVerticalStrut(8));
        card.add(Ui.left(new JLabel("OCR languages for lyrics and text")));
        ocr.putClientProperty("JTextField.placeholderText", "default");
        ocr.setMaximumSize(new Dimension(320, ocr.getPreferredSize().height));
        card.add(Ui.left(ocr));
        card.add(Box.createVerticalStrut(6));
        card.add(Ui.left(straighten));
        card.add(Box.createVerticalStrut(8));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        uploadBtn.setEnabled(false);
        uploadBtn.putClientProperty("JButton.buttonType", "default");
        uploadBtn.addActionListener(e -> upload());
        row.add(uploadBtn);
        row.add(Box.createHorizontalStrut(10));
        row.add(uploadMsg);
        card.add(Ui.left(row));
        return card;
    }

    void chooseFiles() {
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new FileNameExtensionFilter("PDFs and page images", "pdf", "png", "jpg", "jpeg", "tif", "tiff"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            setChosen(java.util.Arrays.stream(fc.getSelectedFiles()).map(File::toPath).toList());
        }
    }

    void setChosen(List<Path> files) {
        chosen.clear();
        chosen.addAll(files);
        chosenLabel.setText(files.isEmpty() ? " " : "Selected: " + String.join(", ",
                files.stream().map(p -> p.getFileName().toString()).toList()));
        uploadBtn.setEnabled(!files.isEmpty());
    }

    private TransferHandler dropHandler() {
        return new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    List<Path> ok = files.stream().filter(f -> f.isFile() && accepted(f.getName())).map(File::toPath).toList();
                    if (ok.isEmpty()) {
                        uploadMsg.setText("Only PDFs and page images (PNG, JPEG, TIFF) can be transcribed.");
                        return false;
                    }
                    setChosen(ok);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        };
    }

    static boolean accepted(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".pdf") || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".tif") || n.endsWith(".tiff");
    }

    private void upload() {
        List<Path> files = List.copyOf(chosen);
        uploadBtn.setEnabled(false);
        uploadMsg.setText("Uploading...");
        Async.run(() -> api.createJob(files, ocr.getText(), straighten.isSelected()), job -> {
            setChosen(List.of());
            uploadMsg.setText(" ");
            openJob.accept(job.path("id").asText());
        }, e -> {
            uploadMsg.setText("Upload failed: " + Async.message(e));
            uploadBtn.setEnabled(true);
        });
    }

    // --- jobs ------------------------------------------------------------------------------

    void start() {
        refresh();
    }

    void stop() {
        poll.stop();
    }

    private void refresh() {
        Async.run(api::jobs, list -> {
            jobs.clear();
            list.forEach(jobs::add);
            int selected = jobsTable.getSelectedRow();
            jobsModel.setRowCount(0);
            for (JsonNode j : jobs) {
                String status = j.path("status").asText();
                jobsModel.addRow(new Object[] {j.path("id").asText(), Labels.jobLabel(j),
                        status + (status.equals("done") ? "" : " · " + j.path("stage").asText()), Labels.created(j)});
            }
            if (selected >= 0 && selected < jobs.size()) jobsTable.setRowSelectionInterval(selected, selected);
            renderCombine();
            // Often while a job runs; now and then otherwise, for jobs started elsewhere.
            boolean busy = jobs.stream().anyMatch(Labels::busy);
            poll.setInitialDelay(busy ? 3000 : 15000);
            if (isShowing()) poll.restart();
        }, e -> {
            combineMsg.setText(" ");
            uploadMsg.setText("Could not list the jobs: " + Async.message(e));
        });
    }

    // --- combine parts -----------------------------------------------------------------------

    private JPanel combineCard() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(Ui.card());
        card.add(Ui.left(Ui.heading("Combine parts")));
        card.add(Box.createVerticalStrut(6));
        card.add(Ui.left(Ui.muted(Ui.wrap("Pick parts of finished jobs to make a new score. The parts are lined up"
                + " measure by measure (a missing measure becomes a rest to review); your edits to each part carry"
                + " over. Parts put \"in the piano accompaniment\" are reduced onto a piano grand staff below the"
                + " others, to go with a melody part.", 460))));
        card.add(Box.createVerticalStrut(8));
        combineRows.setLayout(new BoxLayout(combineRows, BoxLayout.Y_AXIS));
        card.add(Ui.left(combineRows));
        card.add(Box.createVerticalStrut(8));
        card.add(Ui.left(new JLabel("Title")));
        combineTitle.putClientProperty("JTextField.placeholderText", "Combined score");
        combineTitle.setMaximumSize(new Dimension(320, combineTitle.getPreferredSize().height));
        card.add(Ui.left(combineTitle));
        card.add(Box.createVerticalStrut(8));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        combineBtn.setEnabled(false);
        combineBtn.addActionListener(e -> combine());
        row.add(combineBtn);
        row.add(Box.createHorizontalStrut(10));
        row.add(combineMsg);
        card.add(Ui.left(row));
        return card;
    }

    /** One part of a finished job: chosen or not, its transposition and where it goes. */
    private final class PartRow extends JPanel {
        final String job, part;
        final JCheckBox box;
        final JComboBox<String> interval = new JComboBox<>();
        final List<String> values = new ArrayList<>();
        final JComboBox<String> role = new JComboBox<>(new String[] {"on its own staff", "in the piano accompaniment"});

        PartRow(JsonNode job, JsonNode part) {
            super(new FlowLayout(FlowLayout.LEFT, 6, 2));
            this.job = job.path("id").asText();
            this.part = part.path("id").asText();
            box = new JCheckBox(Labels.jobLabel(job) + " · " + part.path("name").asText() + " ("
                    + part.path("measures").asInt() + " measures)");
            List<String[]> choices = new ArrayList<>(Labels.INTERVALS);
            if (part.path("transpose_semitones").asInt(0) != 0) choices.add(Labels.CONCERT);
            for (String[] c : choices) {
                values.add(c[0]);
                interval.addItem(c[1]);
            }
            box.addActionListener(e -> updateCombine());
            role.addActionListener(e -> updateCombine());
            add(box);
            add(interval);
            add(role);
            setAlignmentX(0f);
        }

        boolean chosenAs(String r) {
            return box.isSelected() && (role.getSelectedIndex() == 1 ? "piano" : "staff").equals(r);
        }

        ObjectNode spec() {
            ObjectNode o = JSON.createObjectNode().put("job", job).put("part", part);
            String t = values.get(interval.getSelectedIndex());
            if (!t.isEmpty()) o.put("transpose", t);
            return o;
        }
    }

    private void renderCombine() {
        List<JsonNode> done = jobs.stream().filter(j -> j.path("status").asText().equals("done")).limit(20).toList();
        String key = String.join(",", done.stream().map(j -> j.path("id").asText()).toList());
        if (key.equals(combineKey)) return; // keep the user's choices while nothing changed
        Async.run(() -> {
            for (JsonNode j : done) {
                String id = j.path("id").asText();
                if (!partsCache.containsKey(id)) {
                    JsonNode parts;
                    try {
                        parts = api.parts(id);
                    } catch (Exception e) {
                        parts = JSON.createArrayNode();
                    }
                    partsCache.put(id, parts);
                }
            }
            return done;
        }, list -> {
            combineKey = key;
            combineRows.removeAll();
            partRows.clear();
            for (JsonNode j : list) {
                for (JsonNode p : partsCache.get(j.path("id").asText())) {
                    PartRow r = new PartRow(j, p);
                    partRows.add(r);
                    combineRows.add(r);
                }
            }
            if (partRows.isEmpty()) combineRows.add(Ui.left(Ui.muted("No finished jobs yet.")));
            combineRows.revalidate();
            combineRows.repaint();
            updateCombine();
        }, e -> { });
    }

    private void updateCombine() {
        // A piano accompaniment goes with at least one part on its own staff.
        combineBtn.setEnabled(partRows.stream().anyMatch(r -> r.chosenAs("staff")));
    }

    private void combine() {
        ObjectNode body = JSON.createObjectNode();
        String title = combineTitle.getText().strip();
        if (title.isEmpty()) body.putNull("title"); else body.put("title", title);
        ArrayNode parts = body.putArray("parts"), piano = body.putArray("piano");
        for (PartRow r : partRows) {
            if (r.chosenAs("staff")) parts.add(r.spec());
            if (r.chosenAs("piano")) piano.add(r.spec());
        }
        combineBtn.setEnabled(false);
        combineMsg.setText("Combining...");
        Async.run(() -> api.createScore(body), job -> {
            combineMsg.setText(" ");
            updateCombine();
            openJob.accept(job.path("id").asText());
        }, e -> {
            combineMsg.setText("Could not combine: " + Async.message(e));
            updateCombine();
        });
    }
}

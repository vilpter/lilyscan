package org.lilyscan.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * The Source pane: each page as the engine read it, every event boxed by confidence (green,
 * amber, red), the measures that need review outlined, the selected measure highlighted.
 * Clicking a measure selects it everywhere.
 */
final class SourcePane extends JPanel implements Scrollable {
    private static final BasicStroke EVENT = new BasicStroke(1.5f);
    private static final BasicStroke LOW = new BasicStroke(2.5f);
    private static final BasicStroke ISSUE = new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
            new float[] {6f, 3f}, 0f);
    private static final BasicStroke SELECTED = new BasicStroke(3f);

    private record Box(String measure, String cls, Rectangle2D r, String tip) {}

    private final BiConsumer<String, String> select;
    private final List<PageView> pages = new ArrayList<>();
    private String selected;
    private double zoom = 1; // 1: the pages fit the pane's width

    /** Multiplies the zoom by {@code factor}; 0 fits the width again. Returns the new zoom. */
    double zoom(double factor) {
        zoom = factor == 0 ? 1 : Math.max(0.25, Math.min(8, zoom * factor));
        for (Component c : getComponents()) c.invalidate();
        revalidate();
        repaint();
        if (selected != null) setSelected(selected, true); // keep the selection in view
        return zoom;
    }

    SourcePane(BiConsumer<String, String> select) {
        this.select = select;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }

    /** Lays out the review's pages; their images arrive later through {@link #setImage}. */
    void show(JsonNode review) {
        removeAll();
        pages.clear();
        JsonNode list = review.path("pages");
        if (list.isEmpty()) {
            String why = review.path("combined_from").isNull() || review.path("combined_from").isMissingNode()
                    ? "No page images: the engine did not save its project for this job."
                    : "A combined score has no pages of its own: open the jobs its parts came from to see them.";
            JLabel l = new JLabel(Ui.wrap(why, 280), SwingConstants.LEFT);
            l.setAlignmentX(0f);
            add(l);
        }
        for (int i = 0; i < list.size(); i++) {
            JsonNode p = list.get(i);
            PageView v = new PageView(p.path("width").asDouble(1), p.path("height").asDouble(1));
            for (JsonNode m : review.path("measures")) {
                for (JsonNode e : m.path("events")) {
                    JsonNode b = e.path("bbox");
                    if (b.isObject() && b.path("page").asInt(-1) == i) {
                        v.events.add(new Box(null, Labels.confidence(e.get("confidence")), rect(b, 2), null));
                    }
                }
                JsonNode b = m.path("bbox");
                if (b.isObject() && b.path("page").asInt(-1) == i) {
                    v.measures.add(new Box(m.path("id").asText(), m.path("issues").isEmpty() ? "" : "issue", rect(b, 6),
                            m.path("part_name").asText() + " · m. " + m.path("number").asText()));
                }
            }
            pages.add(v);
            add(v);
        }
        revalidate();
        repaint();
    }

    int pageCount() {
        return pages.size();
    }

    void setImage(int index, BufferedImage image) {
        if (index < pages.size()) {
            pages.get(index).image = image;
            pages.get(index).repaint();
        }
    }

    private static Rectangle2D rect(JsonNode b, double pad) {
        return new Rectangle2D.Double(b.path("x").asDouble() - pad, b.path("y").asDouble() - pad,
                b.path("w").asDouble() + 2 * pad, b.path("h").asDouble() + 2 * pad);
    }

    /** Highlights {@code id}; scrolls it into the middle of the view when {@code reveal}. */
    void setSelected(String id, boolean reveal) {
        selected = id;
        for (Component c : getComponents()) c.repaint();
        if (reveal) javax.swing.SwingUtilities.invokeLater(() -> {
            if (!id.equals(selected)) return;
            if (getParent() != null) getParent().validate();
            reveal(id);
        });
    }

    /** Scrolls measure {@code id} into the middle of the view. */
    private void reveal(String id) {
        for (PageView v : pages) {
            for (Box b : v.measures) {
                if (b.measure.equals(id)) {
                    centre(v, v.toView(b.r).getBounds());
                    return;
                }
            }
        }
    }

    /** Scrolls so that {@code r} (in {@code v}'s coordinates) is in the middle of the viewport. */
    static void centre(JComponent v, Rectangle r) {
        java.awt.Container viewport = v.getParent() == null ? null : v.getParent().getParent();
        int w = viewport == null ? r.width : viewport.getWidth(), h = viewport == null ? r.height : viewport.getHeight();
        v.scrollRectToVisible(new Rectangle(r.x + r.width / 2 - w / 2, r.y + r.height / 2 - h / 2, w, h));
    }

    private final class PageView extends JComponent {
        final double width, height;
        final List<Box> events = new ArrayList<>();
        final List<Box> measures = new ArrayList<>();
        BufferedImage image;

        PageView(double width, double height) {
            this.width = width;
            this.height = height;
            setAlignmentX(0f);
            setToolTipText("");
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    Box b = at(e.getX(), e.getY());
                    if (b != null) select.accept(b.measure, "source");
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    setCursor(at(e.getX(), e.getY()) != null ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                            : java.awt.Cursor.getDefaultCursor());
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        double scale() {
            int w = SourcePane.this.getParent() == null ? 600 : SourcePane.this.getParent().getWidth();
            return Math.max(0.05, (w - 4) / width) * zoom;
        }

        @Override
        public Dimension getPreferredSize() {
            double s = scale();
            return new Dimension((int) (width * s), (int) (height * s) + 8);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        Rectangle2D toView(Rectangle2D r) {
            double s = scale();
            return new Rectangle2D.Double(r.getX() * s, r.getY() * s, r.getWidth() * s, r.getHeight() * s);
        }

        /** The smallest measure box under the point. */
        Box at(int x, int y) {
            Box best = null;
            for (Box b : measures) {
                Rectangle2D v = toView(b.r);
                if (v.contains(x, y) && (best == null || v.getWidth() * v.getHeight() < toView(best.r).getWidth() * toView(best.r).getHeight())) {
                    best = b;
                }
            }
            return best;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            Box b = at(e.getX(), e.getY());
            return b == null ? null : b.tip;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double s = scale();
            int w = (int) (width * s), h = (int) (height * s);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            if (image != null) g.drawImage(image, 0, 0, w, h, null);
            else {
                g.setColor(Ui.UNKNOWN);
                g.drawString("Loading the page...", 12, 24);
            }
            for (Box b : events) {
                g.setColor(Ui.confidence(b.cls));
                g.setStroke(b.cls.equals("low") ? LOW : EVENT);
                g.draw(toView(b.r));
            }
            for (Box b : measures) {
                Rectangle2D v = toView(b.r);
                if (b.measure.equals(selected)) {
                    g.setColor(Ui.SEL_FILL);
                    g.fill(v);
                    g.setColor(Ui.SEL);
                    g.setStroke(SELECTED);
                    g.draw(v);
                } else if (b.cls.equals("issue")) {
                    g.setColor(Ui.ISSUE);
                    g.setStroke(ISSUE);
                    g.draw(v);
                }
            }
            g.dispose();
        }
    }

    // Scrollable: track the viewport's width, so pages fit it.
    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
        return o == SwingConstants.VERTICAL ? r.height - 24 : r.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return zoom <= 1;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}

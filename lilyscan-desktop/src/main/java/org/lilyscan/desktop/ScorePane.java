package org.lilyscan.desktop;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * The Engraved pane: LilyPond's SVG pages, drawn by jsvg. Each note links to its line of
 * source (point and click); the review's measure map turns that line into a measure, so a
 * click selects the measure everywhere and the selected measure's notes turn blue.
 */
final class ScorePane extends JPanel implements Scrollable {
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

    ScorePane(BiConsumer<String, String> select) {
        this.select = select;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(Color.WHITE);
    }

    /** An engraved page, parsed and ready to draw. */
    record Page(SVGDocument doc, LilySvg.Page links) {
        static Page load(byte[] svg) throws IOException {
            SVGDocument doc = new SVGLoader().load(new ByteArrayInputStream(svg), URI.create("file:/score.svg"),
                    LoaderContext.createDefault());
            if (doc == null) throw new IOException("the engraved page could not be read");
            return new Page(doc, LilySvg.parse(svg));
        }
    }

    /** {@code byLine} maps {@code "file:line"} to a measure id. */
    void show(List<Page> list, Map<String, String> byLine) {
        removeAll();
        pages.clear();
        for (Page p : list) {
            PageView v = new PageView(p, byLine);
            pages.add(v);
            add(v);
        }
        if (list.isEmpty()) {
            JLabel l = Ui.muted("Nothing engraved yet.");
            l.setAlignmentX(0f);
            add(l);
        }
        revalidate();
        repaint();
    }

    void clear(String message) {
        removeAll();
        pages.clear();
        JLabel l = Ui.muted(message);
        l.setAlignmentX(0f);
        add(l);
        revalidate();
        repaint();
    }

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
            for (int k = 0; k < v.links.size(); k++) {
                if (id.equals(v.measureOf.get(k))) {
                    // The middle of the measure's notes on this page.
                    Rectangle2D all = null;
                    for (int j = 0; j < v.links.size(); j++) {
                        if (!id.equals(v.measureOf.get(j))) continue;
                        Rectangle2D r = v.toView(v.links.get(j).box());
                        if (all == null) all = r; else all.add(r);
                    }
                    // Notes of an overfull line can lie past the page's edge: show what is on it.
                    Rectangle2D shown = all.createIntersection(new Rectangle2D.Double(0, 0, v.getWidth(), v.getHeight()));
                    SourcePane.centre(v, (shown.isEmpty() ? all : shown).getBounds());
                    return;
                }
            }
        }
    }

    /** The component and point (in its coordinates) at the middle of a note of measure {@code id}. */
    record Spot(JComponent page, java.awt.Point point) {}

    Spot spotOf(String id) {
        for (PageView v : pages) {
            for (int k = 0; k < v.links.size(); k++) {
                if (id.equals(v.measureOf.get(k))) {
                    Rectangle2D r = v.toView(v.links.get(k).box());
                    return new Spot(v, new java.awt.Point((int) r.getCenterX(), (int) r.getCenterY()));
                }
            }
        }
        return null;
    }

    /** The measures that have engraved notes, in page order. */
    List<String> linkedMeasures() {
        List<String> out = new ArrayList<>();
        for (PageView v : pages) for (String m : v.measureOf) if (m != null && !out.contains(m)) out.add(m);
        return out;
    }

    String selected() {
        return selected;
    }

    private final class PageView extends JComponent {
        final Page page;
        final List<LilySvg.Link> links;
        final List<String> measureOf = new ArrayList<>();
        BufferedImage ink, blue;
        int inkWidth = -1;

        PageView(Page page, Map<String, String> byLine) {
            this.page = page;
            this.links = page.links().links();
            for (LilySvg.Link l : links) measureOf.add(byLine.get(Labels.linkTarget(l.href())));
            setAlignmentX(0f);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    String id = at(e.getX(), e.getY());
                    if (id != null) select.accept(id, "score");
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    setCursor(at(e.getX(), e.getY()) != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            : Cursor.getDefaultCursor());
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        Rectangle2D viewBox() {
            return page.links().viewBox();
        }

        double scale() {
            int w = ScorePane.this.getParent() == null ? 600 : ScorePane.this.getParent().getWidth();
            return Math.max(0.5, (w - 4) / viewBox().getWidth()) * zoom;
        }

        @Override
        public Dimension getPreferredSize() {
            double s = scale();
            return new Dimension((int) (viewBox().getWidth() * s), (int) (viewBox().getHeight() * s) + 8);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        Rectangle2D toView(Rectangle2D r) {
            double s = scale();
            Rectangle2D vb = viewBox();
            return new Rectangle2D.Double((r.getX() - vb.getX()) * s, (r.getY() - vb.getY()) * s, r.getWidth() * s,
                    r.getHeight() * s);
        }

        /** The measure of the smallest link under the point that has one. */
        String at(int x, int y) {
            String best = null;
            double area = Double.MAX_VALUE;
            for (int k = 0; k < links.size(); k++) {
                if (measureOf.get(k) == null) continue;
                Rectangle2D v = toView(links.get(k).box());
                Rectangle2D hit = new Rectangle2D.Double(v.getX() - 2, v.getY() - 2, v.getWidth() + 4, v.getHeight() + 4);
                if (hit.contains(x, y) && v.getWidth() * v.getHeight() < area) {
                    best = measureOf.get(k);
                    area = v.getWidth() * v.getHeight();
                }
            }
            return best;
        }

        /** The page drawn at the current width, and a blue copy for the selected notes. */
        private void render() {
            double s = scale();
            int w = (int) Math.ceil(viewBox().getWidth() * s), h = (int) Math.ceil(viewBox().getHeight() * s);
            if (ink != null && inkWidth == w) return;
            ink = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = ink.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(Color.BLACK);
            page.doc().render(null, g, new ViewBox(0, 0, w, h));
            g.dispose();
            blue = new BufferedImage(ink.getWidth(), ink.getHeight(), BufferedImage.TYPE_INT_ARGB);
            int sel = Ui.SEL.getRGB() & 0xffffff;
            for (int y = 0; y < ink.getHeight(); y++) {
                for (int x = 0; x < ink.getWidth(); x++) {
                    int a = ink.getRGB(x, y) >>> 24;
                    if (a != 0) blue.setRGB(x, y, (a << 24) | sel);
                }
            }
            inkWidth = w;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            render();
            Graphics2D g = (Graphics2D) g0.create();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, ink.getWidth(), ink.getHeight());
            g.drawImage(ink, 0, 0, null);
            if (selected != null) {
                for (int k = 0; k < links.size(); k++) {
                    if (!selected.equals(measureOf.get(k))) continue;
                    Rectangle r = toView(links.get(k).box()).getBounds();
                    r.grow(1, 1);
                    Graphics2D c = (Graphics2D) g.create();
                    c.clip(r);
                    c.setColor(Color.WHITE);
                    c.fill(r);
                    c.drawImage(blue, 0, 0, null);
                    c.dispose();
                }
            }
            g.setColor(new Color(0xdddddd));
            g.drawLine(0, ink.getHeight() + 3, ink.getWidth(), ink.getHeight() + 3);
            g.dispose();
        }
    }

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

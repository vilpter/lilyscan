package org.lilyscan.desktop;

import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.border.Border;

/** The web UI's colours (style.css) and a few shared widgets. */
final class Ui {
    private Ui() {}

    static final Color ACCENT = new Color(0x2d6cdf);
    static final Color HIGH = new Color(0x2e9d3f);
    static final Color MID = new Color(0xe19a1b);
    static final Color LOW = new Color(0xd33b3b);
    static final Color ISSUE = new Color(0xb42be0);
    static final Color UNKNOWN = new Color(0x999999);
    static final Color SEL = new Color(0x2d6cdf);
    static final Color SEL_FILL = new Color(45, 108, 223, 31);

    static Color confidence(String cls) {
        return switch (cls) {
            case "high" -> HIGH;
            case "mid" -> MID;
            case "low" -> LOW;
            default -> UNKNOWN;
        };
    }

    static Color status(String status) {
        return switch (status) {
            case "done" -> HIGH;
            case "failed" -> LOW;
            case "queued", "running" -> MID;
            default -> UNKNOWN;
        };
    }

    /** A rounded outline label, like the web UI's pills and badges. */
    static JLabel pill(String text, Color color) {
        JLabel l = new JLabel(text);
        l.setForeground(color);
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(color, 1, true),
                BorderFactory.createEmptyBorder(1, 6, 1, 6)));
        return l;
    }

    static void pill(JLabel l, String text, Color color) {
        l.setText(text);
        l.setForeground(color);
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(color, 1, true),
                BorderFactory.createEmptyBorder(1, 6, 1, 6)));
    }

    static JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, l.getFont().getSize2D() * 1.25f));
        return l;
    }

    static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(UIManager.getColor("Label.disabledForeground"));
        return l;
    }

    static Border card() {
        Color line = UIManager.getColor("Component.borderColor");
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(line == null ? Color.LIGHT_GRAY : line, 1, true),
                BorderFactory.createEmptyBorder(10, 12, 10, 12));
    }

    static <T extends JComponent> T left(T c) {
        c.setAlignmentX(0f);
        return c;
    }

    /** Wraps a card so a vertical box keeps it at its preferred height (it may grow in width). */
    static JComponent fixedHeight(JComponent c) {
        javax.swing.JPanel w = new javax.swing.JPanel(new java.awt.BorderLayout()) {
            @Override
            public java.awt.Dimension getMaximumSize() {
                return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        w.add(c);
        return w;
    }

    /** Wraps text in HTML so a label breaks lines at {@code width} pixels. */
    static String wrap(String text, int width) {
        return "<html><div style='width:" + width + "px'>" + escape(text) + "</div></html>";
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

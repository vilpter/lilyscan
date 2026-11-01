package org.lilyscan.core.xml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal element tree that serializes exactly as Python's {@code xml.etree.ElementTree}
 * ({@code ET.indent} then {@code ET.tostring(encoding="unicode")}): attributes in insertion
 * order, {@code <tag />} for empty elements, ElementTree's escaping.
 */
public final class EtWriter {
    private EtWriter() {}

    /** One element. {@code text} and {@code tail} as in ElementTree. */
    public static final class El {
        public final String tag;
        public final Map<String, String> attrs = new LinkedHashMap<>();
        public String text;
        public String tail;
        public final List<El> children = new ArrayList<>();

        public El(String tag) {
            this.tag = tag;
        }

        /** {@code ET.SubElement(this, tag, attrs)} with optional text ({@code str(text)}). */
        public El sub(String tag, Object text, String... attrs) {
            El el = new El(tag);
            for (int i = 0; i < attrs.length; i += 2) {
                el.attrs.put(attrs[i], attrs[i + 1]);
            }
            if (text != null) el.text = text.toString();
            children.add(el);
            return el;
        }

        public El sub(String tag) {
            return sub(tag, null);
        }

        public El set(String name, String value) {
            attrs.put(name, value);
            return this;
        }
    }

    /** {@code ET.indent(root, space)}. */
    public static void indent(El root, String space) {
        if (!root.children.isEmpty()) indentChildren(root, 0, space);
    }

    private static void indentChildren(El el, int level, String space) {
        String child = "\n" + space.repeat(level + 1);
        if (el.text == null || el.text.strip().isEmpty()) el.text = child;
        El last = null;
        for (El c : el.children) {
            if (!c.children.isEmpty()) indentChildren(c, level + 1, space);
            if (c.tail == null || c.tail.strip().isEmpty()) c.tail = child;
            last = c;
        }
        if (last.tail.strip().isEmpty()) last.tail = "\n" + space.repeat(level);
    }

    /** {@code ET.tostring(root, encoding="unicode")}. */
    public static String toString(El root) {
        StringBuilder sb = new StringBuilder();
        write(sb, root);
        return sb.toString();
    }

    private static void write(StringBuilder sb, El el) {
        sb.append('<').append(el.tag);
        el.attrs.forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(escapeAttr(v)).append('"'));
        boolean hasText = el.text != null && !el.text.isEmpty();
        if (hasText || !el.children.isEmpty()) {
            sb.append('>');
            if (hasText) sb.append(escapeText(el.text));
            for (El c : el.children) write(sb, c);
            sb.append("</").append(el.tag).append('>');
        } else {
            sb.append(" />");
        }
        if (el.tail != null && !el.tail.isEmpty()) sb.append(escapeText(el.tail));
    }

    static String escapeText(String s) {
        if (s.indexOf('&') < 0 && s.indexOf('<') < 0 && s.indexOf('>') < 0) return s;
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String escapeAttr(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\r' -> sb.append("&#13;");
                case '\n' -> sb.append("&#10;");
                case '\t' -> sb.append("&#09;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}

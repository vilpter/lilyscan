package org.lilyscan.core.xml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * DOM helpers with Python {@code xml.etree.ElementTree} semantics: namespace-stripped tag names,
 * element children only (comments and processing instructions skipped), {@code .text} as the
 * text before the first child, and {@code find}/{@code findall} over {@code "a/b/*"} paths.
 * External DTDs and entities are never fetched (as expat does not).
 */
public final class Xml {
    private Xml() {}

    public static Document parse(byte[] data) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setValidating(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new ByteArrayInputStream(data));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        } catch (SAXException e) {
            throw new IOException("XML parse error: " + e.getMessage(), e);
        }
    }

    /** The tag without namespace ({@code el.tag} after the reader strips {@code {ns}}). */
    public static String tag(Element el) {
        String local = el.getLocalName();
        return local != null ? local : el.getTagName();
    }

    /** Element children in document order ({@code list(el)}). */
    public static List<Element> children(Element el) {
        List<Element> out = new ArrayList<>();
        NodeList nl = el.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element c) {
                out.add(c);
            }
        }
        return out;
    }

    /** {@code el.findall(path)}: path segments separated by {@code /}; {@code *} matches any tag. */
    public static List<Element> findAll(Element el, String path) {
        List<Element> current = List.of(el);
        for (String seg : path.split("/")) {
            List<Element> next = new ArrayList<>();
            for (Element e : current) {
                for (Element c : children(e)) {
                    if (seg.equals("*") || tag(c).equals(seg)) {
                        next.add(c);
                    }
                }
            }
            current = next;
        }
        return current;
    }

    /** {@code el.find(path)}: the first match, or null. */
    public static Element find(Element el, String path) {
        if (el == null) {
            return null;
        }
        List<Element> all = findAll(el, path);
        return all.isEmpty() ? null : all.get(0);
    }

    /** ElementTree {@code .text}: the character data before the first child element, or null. */
    public static String text(Element el) {
        StringBuilder sb = null;
        NodeList nl = el.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element) {
                break;
            }
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                if (sb == null) {
                    sb = new StringBuilder();
                }
                sb.append(n.getNodeValue());
            }
        }
        return sb == null ? null : sb.toString();
    }

    /** {@code el.get(name, default)} for an un-namespaced attribute. */
    public static String attr(Element el, String name, String dflt) {
        return el.hasAttribute(name) ? el.getAttribute(name) : dflt;
    }

    /** {@code el.get(name)}. */
    public static String attr(Element el, String name) {
        return attr(el, name, null);
    }

    /** Every element in document order, starting with {@code el} itself ({@code el.iter()}). */
    public static List<Element> iter(Element el) {
        List<Element> out = new ArrayList<>();
        collect(el, out);
        return out;
    }

    private static void collect(Element el, List<Element> out) {
        out.add(el);
        for (Element c : children(el)) {
            collect(c, out);
        }
    }
}

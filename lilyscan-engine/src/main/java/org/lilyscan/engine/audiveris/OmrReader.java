package org.lilyscan.engine.audiveris;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.audiveris.omr.score.LogicalPart;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.Part;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SheetStub;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.grid.LineInfo;
import org.audiveris.omr.sheet.rhythm.MeasureStack;
import org.audiveris.omr.sheet.rhythm.Slot;
import org.audiveris.omr.sig.SIGraph;
import org.audiveris.omr.sig.inter.AbstractHorizontalInter;
import org.audiveris.omr.sig.inter.AbstractVerticalInter;
import org.audiveris.omr.sig.inter.LedgerInter;
import org.audiveris.omr.sig.inter.MusicWordInter;
import org.audiveris.omr.sig.inter.OctaveShiftInter;
import org.audiveris.omr.sig.relation.Relation;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.omr.OmrBook;
import org.lilyscan.core.omr.OmrBook.Box;
import org.lilyscan.core.omr.OmrBook.Inter;
import org.lilyscan.core.omr.OmrBook.OmrSheet;
import org.lilyscan.core.omr.OmrBook.OmrStaff;
import org.lilyscan.core.omr.OmrBook.OmrSystem;
import org.lilyscan.core.omr.OmrBook.Stack;
import org.lilyscan.core.omr.OmrException;
import org.lilyscan.core.util.Py;

/**
 * Stage 3: reads a saved Audiveris {@code .omr} project in-process, through Audiveris's own
 * model classes (D15), into an {@link OmrBook}.
 *
 * <p>Replaces the Python reader that re-parsed the project's XML
 * ({@code lilyscan/engine/audiveris/omr.py}). Navigation uses Audiveris's typed API — book,
 * sheet stubs, systems, measure stacks and slots, parts, staves and staff lines, and the
 * symbol interpretation graph with its relations — so a change in Audiveris's model fails the
 * build rather than the parse. Values are taken as the project saved them ({@link JaxbView}), so
 * the book matches the Python reader's — with one documented exception: Audiveris re-derives
 * the bounds of line-based symbols (stems, beams, ledgers, barlines, ...) from their median line
 * when it reloads a sheet, so their boxes can differ by a pixel from the saved rectangle. No
 * Lilyscan stage uses those boxes (geometry is attached from heads and rests, the syllable repair
 * uses lyric items); {@link #readDetailed} reports which kinds were affected. Written against
 * Audiveris 5.11.0 (D7).
 */
public final class OmrReader {
    private OmrReader() {}

    private static final Set<String> CHORDS = Set.of("head-chord", "rest-chord");

    /** The book, and the kinds whose boxes Audiveris re-derived on reload (see class doc). */
    public record Detailed(OmrBook book, Set<String> derivedBoundsKinds) {}

    /** Reads {@code path}; throws {@link OmrException} when it is not a readable project. */
    public static OmrBook read(Path path) {
        return readDetailed(path).book();
    }

    /**
     * Whether Audiveris computes this interpretation's bounds from its geometry when a sheet is
     * reloaded (the horizontal and vertical line families, ledgers, music words, octave shifts),
     * rather than keeping the saved rectangle.
     */
    public static boolean boundsDerivedOnReload(org.audiveris.omr.sig.inter.Inter i) {
        return i instanceof AbstractHorizontalInter || i instanceof AbstractVerticalInter
                || i instanceof LedgerInter || i instanceof MusicWordInter || i instanceof OctaveShiftInter;
    }

    /** {@link #read}, also reporting the kinds whose bounds were derived on reload. */
    public static Detailed readDetailed(Path path) {
        String fileName = path.getFileName().toString();
        Set<String> names = new HashSet<>();
        try (ZipFile z = new ZipFile(path.toFile())) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                names.add(en.nextElement().getName());
            }
        } catch (ZipException e) {
            throw new OmrException(fileName + " is not an .omr project", e);
        } catch (IOException e) {
            throw new OmrException(fileName + ": " + e.getMessage(), e);
        }
        if (!names.contains("book.xml")) {
            throw new OmrException(fileName + ": no book.xml");
        }
        Engine.init();
        Book book = Book.loadBook(path);
        if (book == null) {
            throw new OmrException(fileName + ": Audiveris could not load the project");
        }

        Map<Integer, String> logical = new LinkedHashMap<>();
        for (org.audiveris.omr.score.Score score : book.getScores()) {
            for (LogicalPart lp : score.getLogicalParts()) {
                String name = JaxbView.attr(lp, "name");
                logical.put(intAttr(lp, "id", 0), name == null ? "" : name);
            }
        }

        List<OmrSheet> sheets = new ArrayList<>();
        Set<String> derived = new java.util.TreeSet<>();
        for (SheetStub stub : book.getStubs()) {
            int n = stub.getNumber();
            if (!names.contains("sheet#" + n + "/sheet#" + n + ".xml")) {
                continue; // sheet not processed (e.g. failed); its measures are absent from MusicXML
            }
            Sheet sheet = stub.getSheet();
            if (sheet == null) {
                throw new OmrException(fileName + ": Audiveris could not load sheet " + n);
            }
            Object picture = JaxbView.element(sheet, "picture");
            Object scale = JaxbView.element(sheet, "scale");
            Object interline = scale == null ? null : JaxbView.element(scale, "interline");
            String main = interline == null ? null : JaxbView.attr(interline, "main");
            List<OmrSystem> systems = new ArrayList<>();
            for (SystemInfo system : sheet.getSystems()) {
                systems.add(system(system, derived));
            }
            sheets.add(new OmrSheet(n,
                    picture == null ? 0 : intAttr(picture, "width", 0),
                    picture == null ? 0 : intAttr(picture, "height", 0),
                    interline == null ? 20.0 : Py.parseFloat(main == null ? "20" : main),
                    systems));
        }
        return new Detailed(new OmrBook(JaxbView.attr(book, "software-version"), sheets, logical),
                java.util.Collections.unmodifiableSet(derived));
    }

    private static int intAttr(Object o, String name, int dflt) {
        String v = JaxbView.attr(o, name);
        return v == null ? dflt : Py.parseInt(v);
    }

    private static double floatAttr(Object o, String name, double dflt) {
        String v = JaxbView.attr(o, name);
        return v == null ? dflt : Py.parseFloat(v);
    }

    /** Python {@code _float}: absent or empty is None. */
    private static Double optFloat(Object o, String name) {
        String v = JaxbView.attr(o, name);
        return v == null || v.isEmpty() ? null : Py.parseFloat(v);
    }

    private static OmrSystem system(SystemInfo sys, Set<String> derived) {
        List<Stack> stacks = new ArrayList<>();
        for (MeasureStack s : sys.getStacks()) {
            double left = floatAttr(s, "left", 0);
            double right = floatAttr(s, "right", left);
            Map<Frac, Double> slots = new LinkedHashMap<>();
            for (Slot sl : s.getSlots()) {
                String t = JaxbView.attr(sl, "time-offset");
                slots.put(Frac.parse(t == null ? "0" : t), left + floatAttr(sl, "x-offset", 0));
            }
            stacks.add(new Stack(left, right, slots));
        }

        Map<Integer, List<OmrStaff>> parts = new LinkedHashMap<>();
        for (Part p : sys.getParts()) {
            List<OmrStaff> staves = new ArrayList<>();
            for (Staff st : p.getStaves()) {
                // Only <staff> elements: one-line staves and tablatures are written under other
                // names, and the staff numbering counts plain staves only.
                if (!"staff".equals(JaxbView.rootName(st))) {
                    continue;
                }
                double top = 0.0;
                double bottom = 0.0;
                boolean any = false;
                for (LineInfo line : st.getLines()) {
                    Object points = JaxbView.element(line, "point");
                    if (points == null) {
                        continue;
                    }
                    for (Object pt : (List<?>) points) {
                        double y = ((Point2D) pt).getY();
                        top = any ? Math.min(top, y) : y;
                        bottom = any ? Math.max(bottom, y) : y;
                        any = true;
                    }
                }
                staves.add(new OmrStaff(intAttr(st, "id", 0), top, bottom));
            }
            parts.put(intAttr(p, "id", 0), staves);
        }

        SIGraph sig = sys.getSig();
        List<Inter> inters = new ArrayList<>();
        Map<Integer, Inter> byId = new HashMap<>();
        for (org.audiveris.omr.sig.inter.Inter i : sig.vertexSet()) {
            Inter inter = inter(i);
            if (boundsDerivedOnReload(i)) {
                derived.add(inter.kind());
            }
            inters.add(inter);
            byId.put(inter.id(), inter);
        }
        Map<Integer, Inter> chordOf = new LinkedHashMap<>();
        for (Relation rel : sig.edgeSet()) {
            if (!"containment".equals(JaxbView.rootName(rel))) {
                continue;
            }
            Inter source = byId.get(intAttr(sig.getEdgeSource(rel), "id", 0));
            if (source != null && CHORDS.contains(source.kind())) {
                chordOf.put(intAttr(sig.getEdgeTarget(rel), "id", 0), source);
            }
        }
        return new OmrSystem(stacks, parts, inters, chordOf, "true".equals(JaxbView.attr(sys, "indented")));
    }

    private static Inter inter(org.audiveris.omr.sig.inter.Inter i) {
        String kind = JaxbView.rootName(i);
        String staff = JaxbView.attr(i, "staff");
        String step = "head".equals(kind) ? JaxbView.attr(i, "pitch") : null;
        Rectangle r = (Rectangle) JaxbView.element(i, "bounds");
        return new Inter(
                intAttr(i, "id", 0),
                kind,
                JaxbView.attr(i, "shape"),
                optFloat(i, "grade"),
                optFloat(i, "ctx-grade"),
                staff == null || staff.isEmpty() ? null : Py.parseInt(staff),
                r == null ? null : new Box(r.x, r.y, r.width, r.height),
                step == null || step.isEmpty() ? null : Py.round(Py.parseFloat(step)),
                JaxbView.attr(i, "value"),
                JaxbView.attr(i, "kind"));
    }
}

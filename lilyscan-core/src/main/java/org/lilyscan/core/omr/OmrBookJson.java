package org.lilyscan.core.omr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lilyscan.core.ir.Frac;
import org.lilyscan.core.json.PyJson;
import org.lilyscan.core.omr.OmrBook.Box;
import org.lilyscan.core.omr.OmrBook.Inter;
import org.lilyscan.core.omr.OmrBook.OmrSheet;
import org.lilyscan.core.omr.OmrBook.OmrStaff;
import org.lilyscan.core.omr.OmrBook.OmrSystem;
import org.lilyscan.core.omr.OmrBook.Stack;

/**
 * The {@link OmrBook} as JSON, in the layout of {@code book_dump} in
 * {@code scripts/java_parity.py}: what the Python reader extracts, so the Java reader can be
 * compared with it, and a book can be cached or inspected without reloading the project.
 */
public final class OmrBookJson {
    private OmrBookJson() {}

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** {@code json.dumps(book_dump(book), indent=1) + "\n"}. */
    public static String write(OmrBook book) {
        return PyJson.write(dump(book), PyJson.Style.jsonDumps(1)) + "\n";
    }

    public static ObjectNode dump(OmrBook book) {
        ObjectNode root = F.objectNode();
        if (book.softwareVersion() == null) {
            root.putNull("software_version");
        } else {
            root.put("software_version", book.softwareVersion());
        }
        ArrayNode lps = root.putArray("logical_parts");
        for (Map.Entry<Integer, String> e : book.logicalParts().entrySet()) {
            lps.addArray().add(e.getKey()).add(e.getValue());
        }
        ArrayNode sheets = root.putArray("sheets");
        for (OmrSheet sh : book.sheets()) {
            ObjectNode s = sheets.addObject();
            s.put("number", sh.number());
            s.put("width", sh.width());
            s.put("height", sh.height());
            s.put("interline", sh.interline());
            ArrayNode systems = s.putArray("systems");
            for (OmrSystem sy : sh.systems()) {
                ObjectNode y = systems.addObject();
                y.put("starts_movement", sy.startsMovement());
                ArrayNode stacks = y.putArray("stacks");
                for (Stack st : sy.stacks()) {
                    ObjectNode t = stacks.addObject();
                    t.put("left", st.left());
                    t.put("right", st.right());
                    ArrayNode slots = t.putArray("slots");
                    for (Map.Entry<Frac, Double> e : st.slots().entrySet()) {
                        slots.addArray().add(e.getKey().toString()).add(e.getValue());
                    }
                }
                ArrayNode parts = y.putArray("parts");
                for (Map.Entry<Integer, List<OmrStaff>> e : sy.parts().entrySet()) {
                    ArrayNode p = parts.addArray().add(e.getKey());
                    ArrayNode staves = p.addArray();
                    for (OmrStaff o : e.getValue()) {
                        staves.addArray().add(o.id()).add(o.top()).add(o.bottom());
                    }
                }
                ArrayNode inters = y.putArray("inters");
                for (Inter i : sy.inters()) {
                    ArrayNode a = inters.addArray();
                    a.add(i.id());
                    a.add(i.kind());
                    a.add(i.shape());
                    a.add(i.grade());
                    a.add(i.ctxGrade());
                    a.add(i.staff());
                    if (i.box() == null) {
                        a.addNull();
                    } else {
                        a.addArray().add(i.box().x()).add(i.box().y()).add(i.box().w()).add(i.box().h());
                    }
                    a.add(i.step());
                    a.add(i.value());
                    a.add(i.role());
                }
                ArrayNode chordOf = y.putArray("chord_of");
                for (Map.Entry<Integer, Inter> e : sy.chordOf().entrySet()) {
                    chordOf.addArray().add(e.getKey()).add(e.getValue().id());
                }
            }
        }
        return root;
    }

    private static Double dbl(JsonNode n) {
        return n == null || n.isNull() ? null : n.asDouble();
    }

    private static Integer integer(JsonNode n) {
        return n == null || n.isNull() ? null : n.asInt();
    }

    private static String str(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    public static OmrBook load(JsonNode root) {
        Map<Integer, String> logical = new LinkedHashMap<>();
        for (JsonNode lp : root.get("logical_parts")) {
            logical.put(lp.get(0).asInt(), lp.get(1).asText());
        }
        List<OmrSheet> sheets = new ArrayList<>();
        for (JsonNode s : root.get("sheets")) {
            List<OmrSystem> systems = new ArrayList<>();
            for (JsonNode y : s.get("systems")) {
                List<Stack> stacks = new ArrayList<>();
                for (JsonNode t : y.get("stacks")) {
                    Map<Frac, Double> slots = new LinkedHashMap<>();
                    for (JsonNode sl : t.get("slots")) {
                        slots.put(Frac.parse(sl.get(0).asText()), sl.get(1).asDouble());
                    }
                    stacks.add(new Stack(t.get("left").asDouble(), t.get("right").asDouble(), slots));
                }
                Map<Integer, List<OmrStaff>> parts = new LinkedHashMap<>();
                for (JsonNode p : y.get("parts")) {
                    List<OmrStaff> staves = new ArrayList<>();
                    for (JsonNode o : p.get(1)) {
                        staves.add(new OmrStaff(o.get(0).asInt(), o.get(1).asDouble(), o.get(2).asDouble()));
                    }
                    parts.put(p.get(0).asInt(), staves);
                }
                List<Inter> inters = new ArrayList<>();
                Map<Integer, Inter> byId = new HashMap<>();
                for (JsonNode a : y.get("inters")) {
                    JsonNode b = a.get(6);
                    Box box = b.isNull() ? null
                            : new Box(b.get(0).asDouble(), b.get(1).asDouble(), b.get(2).asDouble(), b.get(3).asDouble());
                    Inter i = new Inter(a.get(0).asInt(), str(a.get(1)), str(a.get(2)), dbl(a.get(3)), dbl(a.get(4)),
                            integer(a.get(5)), box, integer(a.get(7)), str(a.get(8)), str(a.get(9)));
                    inters.add(i);
                    byId.put(i.id(), i);
                }
                Map<Integer, Inter> chordOf = new LinkedHashMap<>();
                for (JsonNode c : y.get("chord_of")) {
                    chordOf.put(c.get(0).asInt(), byId.get(c.get(1).asInt()));
                }
                systems.add(new OmrSystem(stacks, parts, inters, chordOf, y.get("starts_movement").asBoolean()));
            }
            sheets.add(new OmrSheet(s.get("number").asInt(), s.get("width").asInt(), s.get("height").asInt(),
                    s.get("interline").asDouble(), systems));
        }
        return new OmrBook(str(root.get("software_version")), sheets, logical);
    }
}

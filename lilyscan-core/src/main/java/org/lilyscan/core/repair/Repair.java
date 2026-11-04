package org.lilyscan.core.repair;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One repair's log entry (Python {@code lilyscan.repair.Repair}). */
public record Repair(String rule, String part, Integer staff, String detail, List<String> measures) {

    public Repair(String rule, String part, Integer staff, String detail) {
        this(rule, part, staff, detail, List.of());
    }

    /** {@code dataclasses.asdict}: the fields in declaration order. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", rule);
        m.put("part", part);
        m.put("staff", staff);
        m.put("detail", detail);
        m.put("measures", measures);
        return m;
    }

    /** {@code m.number or str(m.index + 1)}: how the log names a measure. */
    static String label(org.lilyscan.core.ir.Measure m) {
        return m.number != null && !m.number.isEmpty() ? m.number : Integer.toString(m.index + 1);
    }

    /** {@code Provenance(stage="repair", rule=rule, before=...)} with the keys in order. */
    static org.lilyscan.core.ir.Provenance provenance(String rule, Object... keyValues) {
        Map<String, Object> before = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) before.put((String) keyValues[i], keyValues[i + 1]);
        return new org.lilyscan.core.ir.Provenance("repair", rule, before);
    }
}

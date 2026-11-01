package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Map;
import java.util.Objects;

/** Which stage created or modified an element, and what it was before. */
@JsonPropertyOrder({"stage", "rule", "before"})
public final class Provenance {
    /** {@code "audiveris"}, {@code "musicxml"}, {@code "vector-oracle"}, {@code "repair"}, ... */
    public String stage;
    public String rule;
    /** Plain JSON values only (strings, numbers, booleans, lists, maps), insertion-ordered. */
    public Map<String, Object> before;

    public Provenance() {}

    public Provenance(String stage) {
        this.stage = stage;
    }

    public Provenance(String stage, String rule) {
        this.stage = stage;
        this.rule = rule;
    }

    public Provenance(String stage, String rule, Map<String, Object> before) {
        this.stage = stage;
        this.rule = rule;
        this.before = before;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Provenance p && Objects.equals(p.stage, stage)
                && Objects.equals(p.rule, rule) && Objects.equals(p.before, before);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stage, rule, before);
    }
}

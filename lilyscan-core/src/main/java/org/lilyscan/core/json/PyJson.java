package org.lilyscan.core.json;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

/**
 * Writes a Jackson tree exactly as the Python implementation writes JSON, so files the Java
 * build produces are byte-identical to the Python ones (design-java.md D19).
 *
 * <p>Python's {@code json.dumps} and pydantic's {@code model_dump_json} share the escaping rules
 * (short escapes for quote, backslash, BS, FF, LF, CR and TAB; other control characters as a
 * lowercase four-digit hex escape) and write
 * floats with {@code repr}; they differ in separators, indentation and {@code ensure_ascii},
 * which {@link Style} captures.
 */
public final class PyJson {

    /** Output layout. */
    public record Style(String indent, String itemSeparator, String keySeparator,
            boolean ensureAscii, boolean nanAsNull) {

        /** {@code model.model_dump_json(indent=1)} — how {@code ir/score.json} is written. */
        public static final Style PYDANTIC_INDENT_1 = new Style(" ", ",", ": ", false, true);

        /** {@code model.model_dump_json()} — compact, no spaces. */
        public static final Style PYDANTIC_COMPACT = new Style(null, ",", ":", false, true);

        /** {@code json.dumps(obj, indent=n)} (ASCII-escaped, like Python's default). */
        public static Style jsonDumps(int indent) {
            return new Style(" ".repeat(indent), ",", ": ", true, false);
        }

        /** {@code json.dumps(obj, indent=n, ensure_ascii=False)}. */
        public static Style jsonDumpsUtf8(int indent) {
            return new Style(" ".repeat(indent), ",", ": ", false, false);
        }

        /** {@code json.dumps(obj)} — single line, {@code ", "} and {@code ": "}. */
        public static final Style JSON_DUMPS_COMPACT = new Style(null, ", ", ": ", true, false);
    }

    private PyJson() {}

    public static String write(JsonNode node, Style style) {
        StringBuilder sb = new StringBuilder();
        write(sb, node, style, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, JsonNode node, Style style, int depth) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
        } else if (node.isObject()) {
            if (node.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            boolean first = true;
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!first) {
                    sb.append(style.itemSeparator());
                }
                first = false;
                newline(sb, style, depth + 1);
                string(sb, e.getKey(), style.ensureAscii());
                sb.append(style.keySeparator());
                write(sb, e.getValue(), style, depth + 1);
            }
            newline(sb, style, depth);
            sb.append('}');
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (JsonNode item : node) {
                if (!first) {
                    sb.append(style.itemSeparator());
                }
                first = false;
                newline(sb, style, depth + 1);
                write(sb, item, style, depth + 1);
            }
            newline(sb, style, depth);
            sb.append(']');
        } else if (node.isTextual()) {
            string(sb, node.textValue(), style.ensureAscii());
        } else if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
        } else if (node.isIntegralNumber()) {
            sb.append(node.bigIntegerValue().toString());
        } else if (node.isNumber()) {
            double d = node.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append(style.nanAsNull() ? "null"
                        : Double.isNaN(d) ? "NaN" : d > 0 ? "Infinity" : "-Infinity");
            } else {
                sb.append(floatRepr(d));
            }
        } else {
            throw new IllegalArgumentException("unsupported JSON node: " + node.getNodeType());
        }
    }

    private static void newline(StringBuilder sb, Style style, int depth) {
        if (style.indent() != null) {
            sb.append('\n');
            sb.append(style.indent().repeat(depth));
        }
    }

    private static void string(StringBuilder sb, String s, boolean ensureAscii) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || (ensureAscii && c > 0x7e)) {
                        // Java strings are UTF-16, so astral characters are already the
                        // surrogate pair Python's ensure_ascii writes.
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * Python's {@code repr(float)}: the shortest digits that round-trip (Java 19+
     * {@code Double.toString} produces the same shortest, closest digits), laid out in fixed
     * notation unless the decimal exponent is below -4 or at least 16.
     */
    public static String floatRepr(double d) {
        if (d == 0.0) {
            return (Double.doubleToRawLongBits(d) < 0) ? "-0.0" : "0.0";
        }
        String sign = d < 0 ? "-" : "";
        double a = Math.abs(d);
        BigDecimal bd = new BigDecimal(Double.toString(a)).stripTrailingZeros();
        if (bd.precision() == 2) {
            bd = shortestOneDigit(a, bd);
        }
        String digits = bd.unscaledValue().toString();
        // value = 0.digits x 10^decpt
        int decpt = digits.length() - bd.scale();
        if (decpt > -4 && decpt <= 16) {
            if (decpt <= 0) {
                return sign + "0." + "0".repeat(-decpt) + digits;
            }
            if (decpt >= digits.length()) {
                return sign + digits + "0".repeat(decpt - digits.length()) + ".0";
            }
            return sign + digits.substring(0, decpt) + "." + digits.substring(decpt);
        }
        return sciRepr(sign, digits, decpt);
    }

    /**
     * Java's {@code Double.toString} never prints a single significant digit: when one would do,
     * it picks the closest two-digit decimal instead (only distinguishable for subnormals, e.g.
     * {@code 4.9E-324} where Python prints {@code 5e-324}). Use a one-digit decimal when one
     * round-trips, preferring the one closer to the exact value.
     */
    private static BigDecimal shortestOneDigit(double a, BigDecimal two) {
        java.math.MathContext one = new java.math.MathContext(1, java.math.RoundingMode.DOWN);
        BigDecimal down = two.round(one); // first digit kept, so never zero
        BigDecimal up = down.add(down.ulp());
        boolean downOk = down.doubleValue() == a;
        boolean upOk = up.doubleValue() == a;
        if (!downOk && !upOk) {
            return two;
        }
        if (downOk && upOk) {
            BigDecimal exact = new BigDecimal(a);
            return exact.subtract(down).abs().compareTo(up.subtract(exact).abs()) <= 0
                    ? down.stripTrailingZeros() : up.stripTrailingZeros();
        }
        return (downOk ? down : up).stripTrailingZeros();
    }

    private static String sciRepr(String sign, String digits, int decpt) {
        String mantissa = digits.length() == 1 ? digits
                : digits.charAt(0) + "." + digits.substring(1);
        int exp = decpt - 1;
        return sign + mantissa + "e" + (exp < 0 ? "-" : "+")
                + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
    }
}

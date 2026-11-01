package org.lilyscan.core.util;

import java.util.regex.Pattern;

/**
 * Python built-in semantics the port must reproduce exactly: {@code float(str)}, {@code int(str)},
 * {@code round(x)} (half to even), {@code str.isdigit()}.
 */
public final class Py {
    private Py() {}

    private static final String DIGITS = "\\d(?:_?\\d)*";
    private static final Pattern FLOAT = Pattern.compile(
            "[+-]?(?:(?:" + DIGITS + ")?\\.(?:" + DIGITS + ")?|" + DIGITS + ")(?:[eE][+-]?" + DIGITS + ")?");
    private static final Pattern SPECIAL = Pattern.compile("[+-]?(?:inf|infinity|nan)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INT = Pattern.compile("[+-]?" + DIGITS,
            Pattern.UNICODE_CHARACTER_CLASS);

    /** Python {@code float(s)}; throws {@link NumberFormatException} where Python raises ValueError. */
    public static double parseFloat(String s) {
        String t = strip(s);
        if (SPECIAL.matcher(t).matches()) {
            String lower = t.toLowerCase();
            boolean neg = lower.startsWith("-");
            return lower.contains("nan") ? Double.NaN
                    : neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (!FLOAT.matcher(t).matches() || t.chars().noneMatch(Character::isDigit)) {
            throw new NumberFormatException("could not convert string to float: '" + s + "'");
        }
        return Double.parseDouble(t.replace("_", ""));
    }

    /** Python {@code int(s)} for a base-10 literal (whitespace, sign and underscores allowed). */
    public static int parseInt(String s) {
        String t = strip(s);
        if (!INT.matcher(t).matches()) {
            throw new NumberFormatException("invalid literal for int() with base 10: '" + s + "'");
        }
        return Integer.parseInt(t.replace("_", "").replace("+", ""));
    }

    /**
     * Python {@code int(x)} of a float: truncation toward zero. NaN raises ValueError in Python
     * (callers that catch it see {@link NumberFormatException}); infinity raises OverflowError.
     */
    public static int truncate(double x) {
        if (Double.isNaN(x)) {
            throw new NumberFormatException("cannot convert float NaN to integer");
        }
        if (Double.isInfinite(x)) {
            throw new ArithmeticException("cannot convert float infinity to integer");
        }
        return (int) x;
    }

    /** Python {@code round(x)} of a float: nearest integer, ties to even. */
    public static int round(double x) {
        return truncate(Math.rint(x));
    }

    /** Python {@code round(x, ndigits)} of a float: the exact binary value rounded half-even. */
    public static double round(double x, int ndigits) {
        if (Double.isNaN(x) || Double.isInfinite(x) || x == 0) return x;
        double r = new java.math.BigDecimal(x).setScale(ndigits, java.math.RoundingMode.HALF_EVEN).doubleValue();
        return r == 0 ? Math.copySign(0.0, x) : r;
    }

    /** Python {@code str(v)} for the scalar values JSON holds. */
    public static String str(Object v) {
        if (v == null) return "None";
        if (v instanceof Boolean b) return b ? "True" : "False";
        if (v instanceof Double || v instanceof Float) return org.lilyscan.core.json.PyJson.floatRepr(((Number) v).doubleValue());
        return v.toString();
    }

    /** Python {@code str.isspace()} for one code point (Unicode White_Space, incl. NBSP). */
    public static boolean isSpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85;
    }

    /** Python {@code str.strip()} (no arguments). */
    public static String strip(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        while (end > start && isSpace(s.codePointBefore(end))) {
            end -= Character.charCount(s.codePointBefore(end));
        }
        return s.substring(start, end);
    }

    /** Python {@code str.rstrip()} (no arguments). */
    public static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && isSpace(s.codePointBefore(end))) {
            end -= Character.charCount(s.codePointBefore(end));
        }
        return s.substring(0, end);
    }

    /** Python {@code str.split()} (no arguments): runs of whitespace, no empty pieces. */
    public static java.util.List<String> split(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && isSpace(s.codePointAt(i))) {
                i += Character.charCount(s.codePointAt(i));
            }
            int start = i;
            while (i < n && !isSpace(s.codePointAt(i))) {
                i += Character.charCount(s.codePointAt(i));
            }
            if (i > start) {
                out.add(s.substring(start, i));
            }
        }
        return out;
    }

    /** {@code len(s)}: code points, not UTF-16 units. */
    public static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    /** Python {@code str.isdigit()} for the decimal digits that {@code int()} accepts. */
    public static boolean isDigit(String s) {
        return s != null && !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }
}

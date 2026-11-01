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
        String t = s.strip();
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
        String t = s.strip();
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

    /** Python {@code str.isdigit()} for the decimal digits that {@code int()} accepts. */
    public static boolean isDigit(String s) {
        return s != null && !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }
}

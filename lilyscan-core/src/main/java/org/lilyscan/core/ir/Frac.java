package org.lilyscan.core.ir;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * An exact rational number: the IR's unit of musical time, in quarter notes.
 *
 * <p>Mirror of Python's {@code fractions.Fraction} as the IR uses it: always normalized (lowest
 * terms, positive denominator), and serialized as {@code "n"} or {@code "n/d"} exactly like
 * {@code str(Fraction)}. Arithmetic is exact and throws on {@code long} overflow, which musical
 * durations never approach.
 */
public final class Frac implements Comparable<Frac> {

    public static final Frac ZERO = new Frac(0, 1);
    public static final Frac ONE = new Frac(1, 1);

    private final long num;
    private final long den;

    private Frac(long num, long den) {
        this.num = num;
        this.den = den;
    }

    /** The integer {@code n}. */
    public static Frac of(long n) {
        return n == 0 ? ZERO : n == 1 ? ONE : new Frac(n, 1);
    }

    /** {@code n / d}, normalized. */
    public static Frac of(long n, long d) {
        if (d == 0) {
            throw new ArithmeticException("Fraction(" + n + ", 0)");
        }
        if (d < 0) {
            n = Math.negateExact(n);
            d = Math.negateExact(d);
        }
        long g = gcd(Math.abs(n), d);
        if (g > 1) {
            n /= g;
            d /= g;
        }
        if (d == 1) {
            return of(n);
        }
        return new Frac(n, d);
    }

    /**
     * Parses {@code "n"}, {@code "n/d"} or a decimal such as {@code "1.5"} (as Python's
     * {@code Fraction(str)} does), with surrounding whitespace allowed.
     */
    public static Frac parse(String s) {
        String t = s.strip();
        int slash = t.indexOf('/');
        try {
            if (slash >= 0) {
                return of(Long.parseLong(t.substring(0, slash).strip()),
                        Long.parseLong(t.substring(slash + 1).strip()));
            }
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                return of(Long.parseLong(t));
            }
            BigDecimal bd = new BigDecimal(t);
            BigInteger unscaled = bd.unscaledValue();
            int scale = bd.scale();
            if (scale <= 0) {
                return of(unscaled.multiply(BigInteger.TEN.pow(-scale)).longValueExact());
            }
            return of(unscaled.longValueExact(), BigInteger.TEN.pow(scale).longValueExact());
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("invalid Fraction literal: '" + s + "'", e);
        }
    }

    /**
     * The closest fraction to {@code x} with denominator at most 1024 — the IR's rule for a
     * duration given as a float ({@code Fraction(x).limit_denominator(1024)}).
     */
    public static Frac fromDouble(double x) {
        return exactDouble(x).limitDenominator(1024);
    }

    private static Frac exactDouble(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            throw new IllegalArgumentException("cannot convert " + x + " to Fraction");
        }
        BigDecimal bd = new BigDecimal(x); // exact binary value
        BigInteger unscaled = bd.unscaledValue();
        int scale = bd.scale();
        BigInteger n;
        BigInteger d;
        if (scale <= 0) {
            n = unscaled.multiply(BigInteger.TEN.pow(-scale));
            d = BigInteger.ONE;
        } else {
            n = unscaled;
            d = BigInteger.TEN.pow(scale);
        }
        BigInteger g = n.gcd(d);
        n = n.divide(g);
        d = d.divide(g);
        // The exact value may not fit a long; limitDenominator works on BigIntegers.
        return BigFrac.limit(n, d, 1024);
    }

    /** CPython's {@code Fraction.limit_denominator}: closest fraction with denominator <= max. */
    public Frac limitDenominator(long maxDenominator) {
        return BigFrac.limit(BigInteger.valueOf(num), BigInteger.valueOf(den), maxDenominator);
    }

    /** BigInteger implementation of limit_denominator (CPython algorithm). */
    private static final class BigFrac {
        static Frac limit(BigInteger n0, BigInteger d0, long maxDen) {
            if (maxDen < 1) {
                throw new IllegalArgumentException("max_denominator should be at least 1");
            }
            BigInteger max = BigInteger.valueOf(maxDen);
            if (d0.compareTo(max) <= 0) {
                return of(n0.longValueExact(), d0.longValueExact());
            }
            BigInteger p0 = BigInteger.ZERO;
            BigInteger q0 = BigInteger.ONE;
            BigInteger p1 = BigInteger.ONE;
            BigInteger q1 = BigInteger.ZERO;
            BigInteger n = n0;
            BigInteger d = d0;
            while (true) {
                BigInteger a = floorDiv(n, d);
                BigInteger q2 = q0.add(a.multiply(q1));
                if (q2.compareTo(max) > 0) {
                    break;
                }
                BigInteger np = p0.add(a.multiply(p1));
                p0 = p1;
                q0 = q1;
                p1 = np;
                q1 = q2;
                BigInteger nd = n.subtract(a.multiply(d));
                n = d;
                d = nd;
                if (d.signum() == 0) {
                    break;
                }
            }
            BigInteger k = floorDiv(max.subtract(q0), q1);
            // Candidates: bound1 = (p0 + k*p1)/(q0 + k*q1), bound2 = p1/q1.
            BigInteger b1n = p0.add(k.multiply(p1));
            BigInteger b1d = q0.add(k.multiply(q1));
            BigInteger b2n = p1;
            BigInteger b2d = q1;
            // |bound2 - self| <= |bound1 - self|  ->  bound2, else bound1  (ties go to bound2)
            BigInteger diff2 = b2n.multiply(d0).subtract(n0.multiply(b2d)).abs()
                    .multiply(b1d);
            BigInteger diff1 = b1n.multiply(d0).subtract(n0.multiply(b1d)).abs()
                    .multiply(b2d);
            if (diff2.compareTo(diff1) <= 0) {
                return of(b2n.longValueExact(), b2d.longValueExact());
            }
            return of(b1n.longValueExact(), b1d.longValueExact());
        }

        private static BigInteger floorDiv(BigInteger a, BigInteger b) {
            BigInteger[] qr = a.divideAndRemainder(b);
            if (qr[1].signum() != 0 && (qr[1].signum() != b.signum())) {
                return qr[0].subtract(BigInteger.ONE);
            }
            return qr[0];
        }
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a == 0 ? 1 : a;
    }

    public long numerator() {
        return num;
    }

    public long denominator() {
        return den;
    }

    public Frac add(Frac o) {
        return of(Math.addExact(Math.multiplyExact(num, o.den), Math.multiplyExact(o.num, den)),
                Math.multiplyExact(den, o.den));
    }

    public Frac subtract(Frac o) {
        return of(Math.subtractExact(Math.multiplyExact(num, o.den), Math.multiplyExact(o.num, den)),
                Math.multiplyExact(den, o.den));
    }

    public Frac multiply(Frac o) {
        return of(Math.multiplyExact(num, o.num), Math.multiplyExact(den, o.den));
    }

    public Frac multiply(long k) {
        return of(Math.multiplyExact(num, k), den);
    }

    public Frac divide(Frac o) {
        if (o.num == 0) {
            throw new ArithmeticException("Fraction division by zero");
        }
        return of(Math.multiplyExact(num, o.den), Math.multiplyExact(den, o.num));
    }

    public Frac divide(long k) {
        return of(num, Math.multiplyExact(den, k));
    }

    public Frac negate() {
        return of(Math.negateExact(num), den);
    }

    public Frac abs() {
        return num < 0 ? negate() : this;
    }

    public int signum() {
        return Long.signum(num);
    }

    public boolean isZero() {
        return num == 0;
    }

    /** {@code math.floor}. */
    public long floor() {
        return Math.floorDiv(num, den);
    }

    /** {@code int(f)}: truncation toward zero. */
    public long truncate() {
        return num / den;
    }

    public double doubleValue() {
        return (double) num / (double) den;
    }

    public Frac min(Frac o) {
        return compareTo(o) <= 0 ? this : o;
    }

    public Frac max(Frac o) {
        return compareTo(o) >= 0 ? this : o;
    }

    public boolean lt(Frac o) {
        return compareTo(o) < 0;
    }

    public boolean le(Frac o) {
        return compareTo(o) <= 0;
    }

    public boolean gt(Frac o) {
        return compareTo(o) > 0;
    }

    public boolean ge(Frac o) {
        return compareTo(o) >= 0;
    }

    @Override
    public int compareTo(Frac o) {
        return Long.compare(Math.multiplyExact(num, o.den), Math.multiplyExact(o.num, den));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Frac f && f.num == num && f.den == den;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(num) * 31 + Long.hashCode(den);
    }

    /** {@code str(Fraction)}: {@code "3/2"}, {@code "0"}, {@code "-1/4"}. */
    @Override
    public String toString() {
        return den == 1 ? Long.toString(num) : num + "/" + den;
    }
}

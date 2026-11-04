package org.lilyscan.core.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FracTest {

    @Test
    void normalizesLikePythonFraction() {
        assertEquals("3/2", Frac.of(6, 4).toString());
        assertEquals("-1/2", Frac.of(1, -2).toString());
        assertEquals("0", Frac.of(0, 7).toString());
        assertEquals("2", Frac.of(4, 2).toString());
        assertEquals(Frac.of(1, 2), Frac.of(2, 4));
    }

    @Test
    void arithmeticIsExact() {
        Frac third = Frac.of(1, 3);
        assertEquals(Frac.ONE, third.add(third).add(third));
        assertEquals(Frac.of(1, 6), Frac.of(1, 2).subtract(third));
        assertEquals(Frac.of(3, 2), Frac.of(1, 2).multiply(3));
        assertEquals(-2, Frac.of(-3, 2).floor());
        assertEquals(-1, Frac.of(-3, 2).truncate());
        assertTrue(Frac.of(2, 3).gt(Frac.of(1, 2)));
    }

    @Test
    void parsesSerializedForms() {
        assertEquals(Frac.of(3, 2), Frac.parse("3/2"));
        assertEquals(Frac.of(3, 2), Frac.parse(" 1.5 "));
        assertEquals(Frac.of(4), Frac.parse("4"));
        assertEquals(Frac.of(-1, 4), Frac.parse("-1/4"));
        assertThrows(IllegalArgumentException.class, () -> Frac.parse("x"));
    }

    @Test
    void floatsAreLimitedToDenominator1024() {
        // Python: Fraction(1/3).limit_denominator(1024) == Fraction(1, 3)
        assertEquals(Frac.of(1, 3), Frac.fromDouble(1.0 / 3));
        assertEquals(Frac.of(3, 2), Frac.fromDouble(1.5));
        // Python: Fraction(0.1).limit_denominator(1024) == Fraction(1, 10)
        assertEquals(Frac.of(1, 10), Frac.fromDouble(0.1));
        // Python: Fraction(3.14159265).limit_denominator(1024) == Fraction(355, 113)
        assertEquals(Frac.of(355, 113), Frac.fromDouble(3.14159265));
    }
}

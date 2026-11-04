package org.lilyscan.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Python's float formatting, rounding, mean and median, with expected values from CPython. */
class PyNumbersTest {
    @Test
    void fixedFormatRoundsTheExactBinaryValueHalfEven() {
        assertEquals("62", Py.fixed(62.5, 0));
        assertEquals("62", Py.fixed(61.5, 0));
        assertEquals("2.67", Py.fixed(2.675, 2));
        assertEquals("-0", Py.fixed(-0.4, 0));
        assertEquals("0.12", Py.fixed(0.125, 2));
    }

    @Test
    void roundToDigits() {
        assertEquals(0.3, Py.round(0.30000000000000004, 4));
        assertEquals(2.67, Py.round(2.675, 2));
        assertEquals(0.1235, Py.round(0.12345, 4));
    }

    @Test
    void meanIsExact() {
        assertEquals(0.2, Py.mean(List.of(0.1, 0.2, 0.3)));
        assertEquals(0.8475, Py.mean(List.of(0.95, 0.87, 0.91, 0.66)));
        assertEquals(3.3333333333333337e-18, Py.mean(List.of(1e-17, 1.0, -1.0)));
    }

    @Test
    void medianOfAnEvenCountIsTheMiddleMean() {
        assertEquals(63.0, Py.median(List.of(60, 64, 62, 70)));
        assertEquals(62.0, Py.median(List.of(70, 62, 50)));
    }
}

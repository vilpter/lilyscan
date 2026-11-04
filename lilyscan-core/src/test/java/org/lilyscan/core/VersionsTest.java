package org.lilyscan.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VersionsTest {

    @Test
    void pinnedVersionsMatchTheDesign() {
        assertEquals("5.11.0", Versions.AUDIVERIS);
        assertEquals("2.26.0", Versions.LILYPOND);
    }
}

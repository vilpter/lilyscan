package org.lilyscan.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EngineAdapterTest {

    @Test
    void audiverisIsOnTheClasspath() {
        // Proves the in-process Audiveris link (D15): the composite build put
        // Audiveris's classes on the engine module's runtime classpath.
        assertTrue(new EngineAdapter().engineAvailable());
    }
}

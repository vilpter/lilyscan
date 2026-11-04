package org.lilyscan.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of tests/test_config.py, plus the Java-specific settings. */
class ConfigTest {

    @Test
    void defaultOcrLanguagesCoverLyricsDefaults() {
        assertEquals(List.of("eng", "lat", "deu", "fra"), List.of(Config.DEFAULT_OCR_LANGUAGES.split("\\+")));
        assertEquals(Config.DEFAULT_OCR_LANGUAGES, Settings.fromEnv(Map.of()).ocrLanguages());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "eng+lat|eng+lat", " ENG+Lat |eng+lat", "eng,deu|eng+deu", "eng+eng+fra|eng+fra",
        "chi_sim+eng|chi_sim+eng", "deu_latf|deu_latf"}, ignoreLeadingAndTrailingWhitespace = false)
    void parsesOcrLanguages(String spec, String expected) {
        assertEquals(expected, Config.parseOcrLanguages(spec));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "+", "en", "english", "eng+../x", "eng;rm", "eng deu"})
    void rejectsOcrLanguages(String spec) {
        assertThrows(IllegalArgumentException.class, () -> Config.parseOcrLanguages(spec));
    }

    @Test
    void envOverride() {
        assertEquals("eng+ita", Settings.fromEnv(Map.of("LILYSCAN_OCR_LANGUAGES", "eng+ita")).ocrLanguages());
    }

    @Test
    void javaSpecificSettings() {
        Settings s = Settings.fromEnv(Map.of());
        assertNull(s.audiverisBin()); // the bundled engine
        assertEquals("inline", s.dispatch());
        assertEquals("3G", s.audiverisMaxHeap());
        assertEquals(300, s.audiverisStepTimeoutS());
        assertThrows(IllegalArgumentException.class, () -> Settings.fromEnv(Map.of("LILYSCAN_DISPATCH", "rq")));
        assertThrows(IllegalArgumentException.class, () -> Settings.fromEnv(Map.of("AUDIVERIS_MAX_HEAP", "lots")));
    }
}

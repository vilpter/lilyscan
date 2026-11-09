package org.lilyscan.core.ir;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.lilyscan.core.json.PyJson;

/**
 * Reads and writes the IR as JSON, byte-compatible with the Python implementation's
 * {@code score.model_dump_json(indent=1)} (design-java.md D19).
 *
 * <p>Reading mirrors pydantic's validation: unknown fields are rejected ({@code extra="forbid"}),
 * enumerated values and ranges are checked, and {@link Frac} accepts a string, an integer, or a
 * float (limited to denominator 1024).
 */
public final class IrJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .visibility(PropertyAccessor.ALL, Visibility.NONE)
            .visibility(PropertyAccessor.FIELD, Visibility.PUBLIC_ONLY)
            .visibility(PropertyAccessor.CREATOR, Visibility.ANY)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .serializationInclusion(JsonInclude.Include.ALWAYS)
            .addModule(new SimpleModule("lilyscan-ir")
                    .addSerializer(Frac.class, new FracSerializer())
                    .addDeserializer(Frac.class, new FracDeserializer()))
            .build();

    private IrJson() {}

    /** The configured mapper (for reports and other IR-adjacent JSON). */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** The JSON tree of any IR object, as pydantic would dump it. */
    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    /** {@code model_dump_json(indent=1)}. */
    public static String toJson(Object value) {
        return PyJson.write(tree(value), PyJson.Style.PYDANTIC_INDENT_1);
    }

    /** {@code model_dump_json()} (compact). */
    public static String toCompactJson(Object value) {
        return PyJson.write(tree(value), PyJson.Style.PYDANTIC_COMPACT);
    }

    public static Score readScore(String json) {
        return read(json, Score.class);
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            T value = MAPPER.readValue(json, type);
            validate(value);
            return value;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid IR JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** Writes {@code ir/score.json} as the Python pipeline does (UTF-8, LF, no trailing newline). */
    public static void writeScore(Score score, Path path) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, toJson(score), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Score readScore(Path path) {
        try {
            return readScore(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A deep copy through the JSON tree. Unlike Python's {@code deepcopy}, objects shared
     * within {@code value} (a barline set on every staff) come back as separate copies.
     */
    public static <T> T deepCopy(T value, Class<T> type) {
        try {
            return MAPPER.treeToValue(tree(value), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Frac codec ----

    private static final class FracSerializer extends JsonSerializer<Frac> {
        @Override
        public void serialize(Frac value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeString(value.toString());
        }
    }

    private static final class FracDeserializer extends JsonDeserializer<Frac> {
        @Override
        public Frac deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken t = p.currentToken();
            if (t == JsonToken.VALUE_STRING) {
                try {
                    return Frac.parse(p.getText());
                } catch (IllegalArgumentException e) {
                    return (Frac) ctxt.handleWeirdStringValue(Frac.class, p.getText(), e.getMessage());
                }
            }
            if (t == JsonToken.VALUE_NUMBER_INT) {
                return Frac.of(p.getLongValue());
            }
            if (t == JsonToken.VALUE_NUMBER_FLOAT) {
                return Frac.fromDouble(p.getDoubleValue());
            }
            if (t == JsonToken.VALUE_TRUE || t == JsonToken.VALUE_FALSE) {
                return (Frac) ctxt.handleUnexpectedToken(Frac.class, p);
            }
            return (Frac) ctxt.handleUnexpectedToken(Frac.class, p);
        }
    }

    // ---- validation (pydantic Literal / Field constraints) ----

    private static final Set<String> KINDS = Set.of("note", "chord", "rest");
    private static final Set<String> STEPS = Set.of("C", "D", "E", "F", "G", "A", "B");
    private static final Set<String> SYLLABIC = Set.of("single", "begin", "middle", "end");
    private static final Set<String> CLEF_SIGNS = Set.of("G", "F", "C", "percussion", "TAB", "none");
    private static final Set<String> MODES = Set.of("major", "minor");
    private static final Set<String> SYMBOLS = Set.of("common", "cut", "normal");
    private static final Set<String> REPEATS = Set.of("forward", "backward");
    private static final Set<String> ENDING_TYPES = Set.of("start", "stop", "discontinue");

    /** Throws {@link IllegalArgumentException} when {@code value} breaks a model constraint. */
    public static void validate(Object value) {
        if (value instanceof Score s) {
            s.parts.forEach(IrJson::validate);
            s.provenance.forEach(IrJson::validate);
        } else if (value instanceof Part p) {
            require(p.id, "Part.id");
            p.staves.forEach(IrJson::validate);
        } else if (value instanceof Staff s) {
            s.measures.forEach(IrJson::validate);
        } else if (value instanceof Measure m) {
            require(m.number, "Measure.number");
            m.clefs.forEach(IrJson::validate);
            if (m.key != null) {
                validate(m.key);
            }
            if (m.time != null) {
                validate(m.time);
            }
            m.voices.forEach(IrJson::validate);
            m.chordSymbols.forEach(IrJson::validate);
            m.directions.forEach(IrJson::validate);
            if (m.leftBarline != null) {
                validate(m.leftBarline);
            }
            if (m.rightBarline != null) {
                validate(m.rightBarline);
            }
        } else if (value instanceof Voice v) {
            v.events.forEach(IrJson::validate);
        } else if (value instanceof Event e) {
            literal(e.kind, KINDS, "Event.kind");
            require(e.offset, "Event.offset");
            require(e.duration, "Event.duration");
            e.notes.forEach(IrJson::validate);
            e.lyrics.forEach(IrJson::validate);
            e.provenance.forEach(IrJson::validate);
        } else if (value instanceof NoteHead n) {
            require(n.pitch, "NoteHead.pitch");
            validate(n.pitch);
        } else if (value instanceof Pitch p) {
            literal(p.step, STEPS, "Pitch.step");
            if (p.alter < -2 || p.alter > 2) {
                throw new IllegalArgumentException("Pitch.alter out of range: " + p.alter);
            }
        } else if (value instanceof Lyric l) {
            require(l.text, "Lyric.text");
            literal(l.syllabic, SYLLABIC, "Lyric.syllabic");
        } else if (value instanceof Clef c) {
            literal(c.sign, CLEF_SIGNS, "Clef.sign");
            require(c.offset, "Clef.offset");
        } else if (value instanceof KeySignature k) {
            if (k.fifths < -7 || k.fifths > 7) {
                throw new IllegalArgumentException("KeySignature.fifths out of range: " + k.fifths);
            }
            if (k.mode != null) {
                literal(k.mode, MODES, "KeySignature.mode");
            }
        } else if (value instanceof TimeSignature t) {
            literal(t.symbol, SYMBOLS, "TimeSignature.symbol");
        } else if (value instanceof ChordSymbol c) {
            require(c.offset, "ChordSymbol.offset");
            require(c.root, "ChordSymbol.root");
            require(c.kind, "ChordSymbol.kind");
        } else if (value instanceof Direction d) {
            require(d.kind, "Direction.kind");
            require(d.offset, "Direction.offset");
        } else if (value instanceof Barline b) {
            require(b.style, "Barline.style");
            if (b.repeat != null) {
                literal(b.repeat, REPEATS, "Barline.repeat");
            }
            if (b.endingType != null) {
                literal(b.endingType, ENDING_TYPES, "Barline.ending_type");
            }
        } else if (value instanceof Provenance p) {
            require(p.stage, "Provenance.stage");
        }
    }

    private static void require(Object v, String what) {
        if (v == null) {
            throw new IllegalArgumentException(what + " is required");
        }
    }

    private static void literal(String v, Set<String> allowed, String what) {
        if (v == null || !allowed.contains(v)) {
            throw new IllegalArgumentException(what + ": invalid value " + v);
        }
    }
}

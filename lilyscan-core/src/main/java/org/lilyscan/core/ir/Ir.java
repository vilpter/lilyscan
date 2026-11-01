package org.lilyscan.core.ir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies of IR objects with pydantic's {@code model_copy} semantics.
 *
 * <p>{@link #deepCopy} mirrors {@code model_copy(deep=True)} (Python {@code copy.deepcopy}):
 * objects shared within the original — a barline set on every staff, a key signature object on
 * several measures — stay shared in the copy. {@link #copy} mirrors {@code model_copy()}: a new
 * object whose fields hold the same references (lists included).
 */
public final class Ir {
    private Ir() {}

    private static final String IR_PACKAGE = Ir.class.getPackageName();

    /** A deep copy that preserves shared references (Python {@code deepcopy}). */
    @SuppressWarnings("unchecked")
    public static <T> T deepCopy(T value) {
        return (T) copy(value, new IdentityHashMap<>());
    }

    /** A shallow copy (Python {@code model_copy()}): fields hold the same references. */
    @SuppressWarnings("unchecked")
    public static <T> T copy(T value) {
        if (value == null || immutable(value)) {
            return value;
        }
        try {
            Class<?> c = value.getClass();
            Object out = c.getDeclaredConstructor().newInstance();
            for (Field f : fields(c)) {
                f.set(out, f.get(value));
            }
            return (T) out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot copy " + value.getClass(), e);
        }
    }

    private static boolean immutable(Object o) {
        return o instanceof String || o instanceof Number || o instanceof Boolean
                || o instanceof Character || o instanceof Frac || o instanceof Tuplet
                || o instanceof Enum<?> || o instanceof Pitch.Key;
    }

    private static Object copy(Object o, IdentityHashMap<Object, Object> memo) {
        if (o == null || immutable(o)) {
            return o;
        }
        Object done = memo.get(o);
        if (done != null) {
            return done;
        }
        if (o instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            memo.put(o, out);
            for (Object x : list) {
                out.add(copy(x, memo));
            }
            return out;
        }
        if (o instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            memo.put(o, out);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(copy(e.getKey(), memo), copy(e.getValue(), memo));
            }
            return out;
        }
        Class<?> c = o.getClass();
        if (!c.getPackageName().equals(IR_PACKAGE)) {
            throw new IllegalArgumentException("not an IR object: " + c.getName());
        }
        try {
            Object out = c.getDeclaredConstructor().newInstance();
            memo.put(o, out);
            for (Field f : fields(c)) {
                f.set(out, copy(f.get(o), memo));
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot copy " + c, e);
        }
    }

    private static final Map<Class<?>, List<Field>> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

    private static List<Field> fields(Class<?> c) {
        return FIELDS.computeIfAbsent(c, k -> {
            List<Field> out = new ArrayList<>();
            for (Field f : k.getFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    out.add(f);
                }
            }
            return out;
        });
    }
}

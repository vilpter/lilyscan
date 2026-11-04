package org.lilyscan.engine.audiveris;

import java.beans.Introspector;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * The persisted view of Audiveris model objects: values as JAXB writes them into the
 * {@code .omr} XML, read from the live objects Audiveris unmarshalled.
 *
 * <p>The Python reader parses that XML; the Java reader navigates Audiveris's own classes (D15).
 * Some Audiveris getters do more than return stored data — {@code Inter.getBounds()} falls back
 * to (and caches) glyph bounds when none were saved — so reading through them would see values
 * the saved project does not hold. This view honours the JAXB bindings instead: the
 * {@code @XmlAttribute}/{@code @XmlElement} names, {@code @XmlJavaTypeAdapter}s, enum values and
 * getter-bound attributes, giving exactly the strings Python reads. It is deliberately small:
 * only attributes and directly bound elements, which is all the reader needs. Field names it
 * binds to are checked by the reader's parity tests against the saved XML.
 */
final class JaxbView {
    private JaxbView() {}

    /** A bound member and its adapter, if any. */
    private record Binding(AccessibleObject member, XmlAdapter<Object, Object> adapter) {
        Object read(Object owner) throws ReflectiveOperationException {
            if (member instanceof Field f) {
                return f.get(owner);
            }
            return ((Method) member).invoke(owner);
        }
    }

    private static final Map<String, Optional<Binding>> CACHE = new ConcurrentHashMap<>();

    /** The XML attribute {@code name} of {@code obj}, as JAXB marshals it; null when absent. */
    static String attr(Object obj, String name) {
        Optional<Binding> b = binding(obj.getClass(), name, true);
        if (b.isEmpty()) {
            return null;
        }
        try {
            Object v = b.get().read(obj);
            if (b.get().adapter() != null) {
                v = b.get().adapter().marshal(v);
            }
            return lexical(v);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read @" + name + " of " + obj.getClass().getName(), e);
        }
    }

    /** The raw value bound to the XML element {@code name} of {@code obj} (no adapter, no getter). */
    static Object element(Object obj, String name) {
        Optional<Binding> b = binding(obj.getClass(), name, false);
        if (b.isEmpty()) {
            return null;
        }
        try {
            return b.get().read(obj);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read <" + name + "> of " + obj.getClass().getName(), e);
        }
    }

    /** The XML element name of {@code obj}'s class ({@code @XmlRootElement}). */
    static String rootName(Object obj) {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            XmlRootElement r = c.getAnnotation(XmlRootElement.class);
            if (r != null) {
                return "##default".equals(r.name()) ? Introspector.decapitalize(c.getSimpleName()) : r.name();
            }
        }
        return null;
    }

    private static String lexical(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Enum<?> e) {
            try {
                XmlEnumValue ev = e.getDeclaringClass().getField(e.name()).getAnnotation(XmlEnumValue.class);
                return ev != null ? ev.value() : e.name();
            } catch (NoSuchFieldException ex) {
                return e.name();
            }
        }
        return v.toString();
    }

    private static Optional<Binding> binding(Class<?> type, String name, boolean attribute) {
        String key = type.getName() + (attribute ? "@" : "<") + name;
        return CACHE.computeIfAbsent(key, k -> Optional.ofNullable(find(type, name, attribute)));
    }

    private static Binding find(Class<?> type, String name, boolean attribute) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && bound(f, f.getName(), name, attribute)) {
                    return binding(f);
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers())
                        && bound(m, property(m.getName()), name, attribute)) {
                    return binding(m);
                }
            }
        }
        return null;
    }

    private static boolean bound(AccessibleObject m, String property, String name, boolean attribute) {
        if (attribute) {
            XmlAttribute a = m.getAnnotation(XmlAttribute.class);
            return a != null && ("##default".equals(a.name()) ? property : a.name()).equals(name);
        }
        XmlElement e = m.getAnnotation(XmlElement.class);
        return e != null && ("##default".equals(e.name()) ? property : e.name()).equals(name);
    }

    private static String property(String getter) {
        if (getter.startsWith("get") && getter.length() > 3) {
            return Introspector.decapitalize(getter.substring(3));
        }
        if (getter.startsWith("is") && getter.length() > 2) {
            return Introspector.decapitalize(getter.substring(2));
        }
        return getter;
    }

    @SuppressWarnings("unchecked")
    private static Binding binding(AccessibleObject member) {
        member.setAccessible(true);
        XmlJavaTypeAdapter ann = member.getAnnotation(XmlJavaTypeAdapter.class);
        XmlAdapter<Object, Object> adapter = null;
        if (ann != null) {
            try {
                Constructor<?> ctor = ann.value().getDeclaredConstructor();
                ctor.setAccessible(true);
                adapter = (XmlAdapter<Object, Object>) ctor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot create adapter " + ann.value().getName()
                        + " for " + ((Member) member).getName(), e);
            }
        }
        return new Binding(member, adapter);
    }
}

package org.roxycode.app.ai;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.jexl3.JexlArithmetic;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlOperator;
import org.apache.commons.jexl3.introspection.JexlMethod;
import org.apache.commons.jexl3.introspection.JexlPropertyGet;
import org.apache.commons.jexl3.introspection.JexlPropertySet;
import org.apache.commons.jexl3.introspection.JexlUberspect;

/**
 * Custom {@link JexlUberspect} wrapper that delegates standard Java property and method lookup first, then falls back
 * to {@code .length}, {@code .includes(x)}, native Java {@code record} component property access, and {@link Map}
 * property assignment.
 */
public final class JsPolyfillUberspect implements JexlUberspect {

    private static final String PROP_LENGTH = "length";
    private static final String METHOD_INCLUDES = "includes";

    private static final JexlPropertyGet LENGTH_PROPERTY_GET = new LengthPropertyGet();
    private static final JexlMethod INCLUDES_METHOD = new IncludesMethod();

    private final JexlUberspect delegate;

    /**
     * Creates a new {@link JsPolyfillUberspect} wrapping the given base {@link JexlUberspect}.
     *
     * @param delegate
     *            the underlying JEXL uberspect
     */
    public JsPolyfillUberspect(JexlUberspect delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public void setClassLoader(ClassLoader loader) {
        delegate.setClassLoader(loader);
    }

    @Override
    public ClassLoader getClassLoader() {
        return delegate.getClassLoader();
    }

    @Override
    public int getVersion() {
        return delegate.getVersion();
    }

    @Override
    public JexlMethod getConstructor(Object ctorHandle, Object... args) {
        return delegate.getConstructor(ctorHandle, args);
    }

    @Override
    public JexlMethod getMethod(Object obj, String method, Object... args) {
        JexlMethod resolved = delegate.getMethod(obj, method, args);
        if (resolved != null) {
            return resolved;
        }
        if (isSupportedIncludes(obj, method, args)) {
            return INCLUDES_METHOD;
        }
        return null;
    }

    @Override
    public List<PropertyResolver> getResolvers(JexlOperator op, Object obj) {
        return delegate.getResolvers(op, obj);
    }

    @Override
    public JexlPropertyGet getPropertyGet(Object obj, Object identifier) {
        return resolvePropertyGet(obj, identifier, null);
    }

    @Override
    public JexlPropertyGet getPropertyGet(List<PropertyResolver> resolvers, Object obj, Object identifier) {
        return resolvePropertyGet(obj, identifier, resolvers);
    }

    @Override
    public JexlPropertySet getPropertySet(Object obj, Object identifier, Object arg) {
        return resolvePropertySet(obj, identifier, arg, null);
    }

    @Override
    public JexlPropertySet getPropertySet(List<PropertyResolver> resolvers, Object obj, Object identifier, Object arg) {
        return resolvePropertySet(obj, identifier, arg, resolvers);
    }

    @Override
    public Iterator<?> getIterator(Object obj) {
        return delegate.getIterator(obj);
    }

    @Override
    public JexlArithmetic.Uberspect getArithmetic(JexlArithmetic arithmetic) {
        return delegate.getArithmetic(arithmetic);
    }

    private JexlPropertySet resolvePropertySet(Object obj, Object identifier, Object arg,
            List<PropertyResolver> resolvers) {
        JexlPropertySet standard;
        if (resolvers == null) {
            standard = delegate.getPropertySet(obj, identifier, arg);
        } else {
            standard = delegate.getPropertySet(resolvers, obj, identifier, arg);
        }
        if (standard != null) {
            return standard;
        }
        if (obj instanceof Map<?, ?>) {
            return new MapPropertySet(identifier);
        }
        return null;
    }

    private JexlPropertyGet resolvePropertyGet(Object obj, Object identifier, List<PropertyResolver> resolvers) {
        if (isMapLengthLookup(obj, identifier)) {
            return LENGTH_PROPERTY_GET;
        }
        JexlPropertyGet resolved;
        if (resolvers == null) {
            resolved = delegate.getPropertyGet(obj, identifier);
        } else {
            resolved = delegate.getPropertyGet(resolvers, obj, identifier);
        }
        if (resolved != null) {
            return resolved;
        }
        if (PROP_LENGTH.equals(identifier) && resolveLength(obj) >= 0) {
            return LENGTH_PROPERTY_GET;
        }
        if (obj != null && obj.getClass().isRecord() && Modifier.isPublic(obj.getClass().getModifiers())
                && identifier instanceof String propName) {
            for (RecordComponent comp : obj.getClass().getRecordComponents()) {
                Method accessor = comp.getAccessor();
                if (comp.getName().equals(propName) && Modifier.isPublic(accessor.getModifiers())) {
                    return new RecordComponentPropertyGet(accessor, propName);
                }
            }
        }
        return null;
    }

    private static boolean isMapLengthLookup(Object obj, Object identifier) {
        if (!PROP_LENGTH.equals(identifier)) {
            return false;
        }
        if (obj instanceof Map<?, ?> map) {
            return !map.containsKey(PROP_LENGTH);
        }
        return false;
    }

    private static int resolveLength(Object obj) {
        if (obj instanceof CharSequence cs) {
            return cs.length();
        }
        if (obj instanceof Collection<?> col) {
            return col.size();
        }
        if (obj instanceof Map<?, ?> map) {
            return map.size();
        }
        if (obj != null && obj.getClass().isArray()) {
            return Array.getLength(obj);
        }
        return -1;
    }

    private static boolean isSupportedIncludes(Object obj, String method, Object... args) {
        if (obj == null || !METHOD_INCLUDES.equals(method) || args == null || args.length != 1) {
            return false;
        }
        return obj instanceof CharSequence || obj instanceof Collection<?> || obj.getClass().isArray();
    }

    private static boolean invokeIncludes(Object target, Object... params) {
        if (params == null || params.length == 0) {
            return false;
        }
        Object needle = params[0];
        if (target instanceof CharSequence cs) {
            if (needle == null) {
                return false;
            }
            return cs.toString().contains(String.valueOf(needle));
        }
        if (target instanceof Collection<?> col) {
            return col.contains(needle);
        }
        if (target != null && target.getClass().isArray()) {
            int len = Array.getLength(target);
            for (int i = 0; i < len; i++) {
                if (Objects.equals(Array.get(target, i), needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class LengthPropertyGet implements JexlPropertyGet {

        @Override
        public Object invoke(Object obj) {
            int length = resolveLength(obj);
            if (length >= 0) {
                return length;
            }
            throw new IllegalArgumentException("Unsupported .length target: " + obj);
        }

        @Override
        public Object tryInvoke(Object obj, Object key) {
            if (!PROP_LENGTH.equals(key) || (obj instanceof Map<?, ?> map && map.containsKey(PROP_LENGTH))) {
                return JexlEngine.TRY_FAILED;
            }
            int length = resolveLength(obj);
            if (length >= 0) {
                return length;
            }
            return JexlEngine.TRY_FAILED;
        }

        @Override
        public boolean tryFailed(Object rval) {
            return rval == JexlEngine.TRY_FAILED;
        }

        @Override
        public boolean isCacheable() {
            return true;
        }
    }

    private static final class RecordComponentPropertyGet implements JexlPropertyGet {

        private final Method accessor;
        private final String propertyName;

        private RecordComponentPropertyGet(Method accessor, String propertyName) {
            this.accessor = accessor;
            this.propertyName = propertyName;
        }

        @Override
        public Object invoke(Object obj) throws Exception {
            return accessor.invoke(obj);
        }

        @Override
        public Object tryInvoke(Object obj, Object key) {
            if (!propertyName.equals(key) || obj == null || !accessor.getDeclaringClass().isInstance(obj)) {
                return JexlEngine.TRY_FAILED;
            }
            try {
                return accessor.invoke(obj);
            } catch (IllegalAccessException | InvocationTargetException e) {
                return JexlEngine.TRY_FAILED;
            }
        }

        @Override
        public boolean tryFailed(Object rval) {
            return rval == JexlEngine.TRY_FAILED;
        }

        @Override
        public boolean isCacheable() {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static final class MapPropertySet implements JexlPropertySet {

        private final Object key;

        private MapPropertySet(Object key) {
            this.key = key;
        }

        @Override
        public Object invoke(Object obj, Object arg) {
            if (!(obj instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Unsupported map property set target: " + obj);
            }
            ((Map<Object, Object>) obj).put(key, arg);
            return arg;
        }

        @Override
        public Object tryInvoke(Object obj, Object targetKey, Object arg) {
            if (!(obj instanceof Map<?, ?>)) {
                return JexlEngine.TRY_FAILED;
            }
            ((Map<Object, Object>) obj).put(targetKey, arg);
            return arg;
        }

        @Override
        public boolean tryFailed(Object rval) {
            return rval == JexlEngine.TRY_FAILED;
        }

        @Override
        public boolean isCacheable() {
            return true;
        }
    }

    private static final class IncludesMethod implements JexlMethod {

        @Override
        public Object invoke(Object obj, Object... params) {
            return invokeIncludes(obj, params);
        }

        @Override
        public Object tryInvoke(String name, Object obj, Object... params) {
            if (!isSupportedIncludes(obj, name, params)) {
                return JexlEngine.TRY_FAILED;
            }
            return invokeIncludes(obj, params);
        }

        @Override
        public boolean tryFailed(Object rval) {
            return rval == JexlEngine.TRY_FAILED;
        }

        @Override
        public boolean isCacheable() {
            return true;
        }

        @Override
        public Class<?> getReturnType() {
            return Boolean.class;
        }
    }
}
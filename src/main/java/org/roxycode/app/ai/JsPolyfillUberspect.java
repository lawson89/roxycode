package org.roxycode.app.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.jexl3.JexlArithmetic;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlOperator;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.introspection.JexlMethod;
import org.apache.commons.jexl3.introspection.JexlPropertyGet;
import org.apache.commons.jexl3.introspection.JexlPropertySet;
import org.apache.commons.jexl3.introspection.JexlUberspect;
import org.apache.commons.lang3.StringUtils;

/**
 * Custom {@link JexlUberspect} wrapper that delegates standard Java property and method lookup first, then falls back
 * to JavaScript/TypeScript polyfill properties, methods, higher-order lambda methods, and provides safe {@code JSON}
 * and {@code Object} globals.
 */
public final class JsPolyfillUberspect implements JexlUberspect {

    public static final JsObjectGlobal OBJECT_GLOBAL = new JsObjectGlobal();

    private static final String PROP_LENGTH = "length";
    private static final String METHOD_INCLUDES = "includes";
    private static final String METHOD_SLICE = "slice";
    private static final String METHOD_TRIM_START = "trimStart";
    private static final String METHOD_TRIM_END = "trimEnd";
    private static final String METHOD_PAD_START = "padStart";
    private static final String METHOD_PAD_END = "padEnd";
    private static final String METHOD_HAS = "has";
    private static final String METHOD_DELETE = "delete";
    private static final String METHOD_INDEX_OF = "indexOf";
    private static final String METHOD_PUSH = "push";
    private static final String METHOD_JOIN = "join";
    private static final String METHOD_MAP = "map";
    private static final String METHOD_FILTER = "filter";
    private static final String METHOD_FIND = "find";
    private static final String METHOD_SOME = "some";
    private static final String METHOD_EVERY = "every";
    private static final String METHOD_FOR_EACH = "forEach";

    private static final int NO_ARGS = 0;
    private static final int SINGLE_ARG = 1;
    private static final int TWO_ARGS = 2;

    private static final JexlPropertyGet LENGTH_PROPERTY_GET = new LengthPropertyGet();

    private final JexlUberspect delegate;
    private final JexlContext context;

    public JsPolyfillUberspect(JexlUberspect delegate, JexlContext context) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.context = context;
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
        return resolvePolyfillMethod(obj, method, args);
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
        return delegate.getPropertySet(obj, identifier, arg);
    }

    @Override
    public JexlPropertySet getPropertySet(List<PropertyResolver> resolvers, Object obj, Object identifier, Object arg) {
        return delegate.getPropertySet(resolvers, obj, identifier, arg);
    }

    @Override
    public Iterator<?> getIterator(Object obj) {
        return delegate.getIterator(obj);
    }

    @Override
    public JexlArithmetic.Uberspect getArithmetic(JexlArithmetic arithmetic) {
        return delegate.getArithmetic(arithmetic);
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

    private JexlMethod resolvePolyfillMethod(Object obj, String method, Object... args) {
        if (!isSupportedPolyfill(obj, method, args)) {
            return null;
        }
        return new PolyfillMethod(method, context);
    }

    static boolean isSupportedPolyfill(Object obj, String method, Object... args) {
        if (obj == null || method == null) {
            return false;
        }
        int argCount = NO_ARGS;
        if (args != null) {
            argCount = args.length;
        }
        if (obj instanceof CharSequence) {
            return isSupportedCharSequenceMethod(method, argCount);
        }
        if (obj instanceof Map<?, ?>) {
            return isSupportedMapMethod(method, argCount);
        }
        if (obj instanceof Collection<?> || obj.getClass().isArray()) {
            return isSupportedSequenceMethod(obj, method, argCount, args);
        }
        return false;
    }

    private static boolean isSupportedCharSequenceMethod(String method, int argCount) {
        switch (method) {
            case METHOD_INCLUDES :
                return argCount == SINGLE_ARG;
            case METHOD_SLICE :
            case METHOD_PAD_START :
            case METHOD_PAD_END :
                return argCount == SINGLE_ARG || argCount == TWO_ARGS;
            case METHOD_TRIM_START :
            case METHOD_TRIM_END :
                return argCount == NO_ARGS;
            default :
                return false;
        }
    }

    private static boolean isSupportedMapMethod(String method, int argCount) {
        return argCount == SINGLE_ARG && (METHOD_HAS.equals(method) || METHOD_DELETE.equals(method));
    }

    private static boolean isSupportedSequenceMethod(Object obj, String method, int argCount, Object... args) {
        switch (method) {
            case METHOD_INCLUDES :
            case METHOD_INDEX_OF :
                return argCount == SINGLE_ARG;
            case METHOD_PUSH :
                return obj instanceof Collection<?>;
            case METHOD_JOIN :
                return argCount == NO_ARGS || argCount == SINGLE_ARG;
            case METHOD_SLICE :
                return argCount == SINGLE_ARG || argCount == TWO_ARGS;
            case METHOD_MAP :
            case METHOD_FILTER :
            case METHOD_FIND :
            case METHOD_SOME :
            case METHOD_EVERY :
            case METHOD_FOR_EACH :
                return argCount == SINGLE_ARG && args[0] instanceof JexlScript;
            default :
                return false;
        }
    }

    static Object invokeCharSequenceMethod(CharSequence target, String method, Object... params) {
        String str = target.toString();
        switch (method) {
            case METHOD_INCLUDES :
                if (params.length == NO_ARGS || params[0] == null) {
                    return false;
                }
                return str.contains(String.valueOf(params[0]));
            case METHOD_SLICE :
                return sliceString(str, params);
            case METHOD_TRIM_START :
                return str.stripLeading();
            case METHOD_TRIM_END :
                return str.stripTrailing();
            case METHOD_PAD_START :
                return padString(str, true, params);
            case METHOD_PAD_END :
                return padString(str, false, params);
            default :
                throw new UnsupportedOperationException("Unsupported string method: " + method);
        }
    }

    @SuppressWarnings("unchecked")
    static Object invokeMapMethod(Map<?, ?> target, String method, Object... params) {
        Object key = null;
        if (params.length > NO_ARGS) {
            key = params[0];
        }
        if (METHOD_HAS.equals(method)) {
            return target.containsKey(key);
        }
        if (METHOD_DELETE.equals(method)) {
            Map<Object, Object> mutableMap = (Map<Object, Object>) target;
            return mutableMap.remove(key) != null;
        }
        throw new UnsupportedOperationException("Unsupported map method: " + method);
    }

    @SuppressWarnings("unchecked")
    static Object invokeSequenceMethod(Object target, String method, JexlContext context, Object... params) {
        if (METHOD_PUSH.equals(method) && target instanceof Collection<?>) {
            Collection<Object> collection = (Collection<Object>) target;
            for (Object item : params) {
                collection.add(item);
            }
            return collection.size();
        }
        List<Object> elements = toElementList(target);
        switch (method) {
            case METHOD_INCLUDES :
                Object needle = null;
                if (params.length > NO_ARGS) {
                    needle = params[0];
                }
                return elements.contains(needle);
            case METHOD_INDEX_OF :
                Object searchItem = null;
                if (params.length > NO_ARGS) {
                    searchItem = params[0];
                }
                return elements.indexOf(searchItem);
            case METHOD_JOIN :
                return joinElements(elements, params);
            case METHOD_SLICE :
                return sliceList(elements, params);
            case METHOD_MAP :
                return mapElements(elements, (JexlScript) params[0], context);
            case METHOD_FILTER :
                return filterElements(elements, (JexlScript) params[0], context);
            case METHOD_FIND :
                return findElement(elements, (JexlScript) params[0], context);
            case METHOD_SOME :
                return someElements(elements, (JexlScript) params[0], context);
            case METHOD_EVERY :
                return everyElements(elements, (JexlScript) params[0], context);
            case METHOD_FOR_EACH :
                forEachElement(elements, (JexlScript) params[0], context);
                return null;
            default :
                throw new UnsupportedOperationException("Unsupported sequence method: " + method);
        }
    }

    private static List<Object> toElementList(Object target) {
        if (target instanceof List<?>) {
            return new ArrayList<>((List<?>) target);
        }
        if (target instanceof Collection<?>) {
            return new ArrayList<>((Collection<?>) target);
        }
        if (target != null && target.getClass().isArray()) {
            int length = Array.getLength(target);
            List<Object> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add(Array.get(target, i));
            }
            return list;
        }
        return Collections.emptyList();
    }

    private static int[] resolveSliceBounds(int length, Object... params) {
        int from = normalizeSliceIndex(toInt(params[0], 0), length);
        int to = length;
        if (params.length > SINGLE_ARG && params[1] != null) {
            to = normalizeSliceIndex(toInt(params[1], length), length);
        }
        return new int[]{from, to};
    }

    private static String sliceString(String str, Object... params) {
        int[] bounds = resolveSliceBounds(str.length(), params);
        if (bounds[1] <= bounds[0]) {
            return "";
        }
        return str.substring(bounds[0], bounds[1]);
    }

    private static List<Object> sliceList(List<Object> elements, Object... params) {
        int[] bounds = resolveSliceBounds(elements.size(), params);
        if (bounds[1] <= bounds[0]) {
            return new ArrayList<>();
        }
        return new ArrayList<>(elements.subList(bounds[0], bounds[1]));
    }

    private static int normalizeSliceIndex(int index, int length) {
        if (index < 0) {
            int resolved = length + index;
            if (resolved < 0) {
                return 0;
            }
            return resolved;
        }
        if (index > length) {
            return length;
        }
        return index;
    }

    private static String padString(String str, boolean padStart, Object... params) {
        int targetLen = toInt(params[0], 0);
        String fill = " ";
        if (params.length > SINGLE_ARG && params[1] != null) {
            fill = String.valueOf(params[1]);
        }
        if (fill.isEmpty()) {
            return str;
        }
        if (padStart) {
            return StringUtils.leftPad(str, targetLen, fill);
        }
        return StringUtils.rightPad(str, targetLen, fill);
    }

    private static String joinElements(List<Object> elements, Object... params) {
        String separator = ",";
        if (params.length > NO_ARGS && params[0] != null) {
            separator = String.valueOf(params[0]);
        }
        return elements.stream().map(String::valueOf).collect(Collectors.joining(separator));
    }

    private static List<Object> mapElements(List<Object> elements, JexlScript fn, JexlContext context) {
        List<Object> mapped = new ArrayList<>(elements.size());
        for (int i = 0; i < elements.size(); i++) {
            mapped.add(executeLambda(fn, context, elements.get(i), i));
        }
        return mapped;
    }

    private static List<Object> filterElements(List<Object> elements, JexlScript fn, JexlContext context) {
        List<Object> filtered = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            Object item = elements.get(i);
            if (isTruthy(executeLambda(fn, context, item, i))) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private static Object findElement(List<Object> elements, JexlScript fn, JexlContext context) {
        for (int i = 0; i < elements.size(); i++) {
            Object item = elements.get(i);
            if (isTruthy(executeLambda(fn, context, item, i))) {
                return item;
            }
        }
        return null;
    }

    private static boolean someElements(List<Object> elements, JexlScript fn, JexlContext context) {
        for (int i = 0; i < elements.size(); i++) {
            if (isTruthy(executeLambda(fn, context, elements.get(i), i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean everyElements(List<Object> elements, JexlScript fn, JexlContext context) {
        for (int i = 0; i < elements.size(); i++) {
            if (!isTruthy(executeLambda(fn, context, elements.get(i), i))) {
                return false;
            }
        }
        return true;
    }

    private static void forEachElement(List<Object> elements, JexlScript fn, JexlContext context) {
        for (int i = 0; i < elements.size(); i++) {
            executeLambda(fn, context, elements.get(i), i);
        }
    }

    private static Object executeLambda(JexlScript fn, JexlContext context, Object item, int index) {
        String[] parameters = fn.getParameters();
        if (parameters != null && parameters.length >= TWO_ARGS) {
            return fn.execute(context, item, index);
        }
        return fn.execute(context, item);
    }

    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d != 0.0 && !Double.isNaN(d);
        }
        if (value instanceof CharSequence cs) {
            return !cs.isEmpty();
        }
        return true;
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof CharSequence cs) {
            try {
                return Integer.parseInt(cs.toString().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
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

    private static final class PolyfillMethod implements JexlMethod {

        private final String method;
        private final JexlContext context;

        PolyfillMethod(String method, JexlContext context) {
            this.method = method;
            this.context = context;
        }

        @Override
        public Object invoke(Object obj, Object... params) {
            Object[] safeParams = params;
            if (safeParams == null) {
                safeParams = new Object[0];
            }
            if (obj instanceof CharSequence cs) {
                return invokeCharSequenceMethod(cs, method, safeParams);
            }
            if (obj instanceof Map<?, ?> map) {
                return invokeMapMethod(map, method, safeParams);
            }
            if (obj instanceof Collection<?> || (obj != null && obj.getClass().isArray())) {
                return invokeSequenceMethod(obj, method, context, safeParams);
            }
            throw new IllegalArgumentException("Unsupported receiver for " + method + ": " + obj);
        }

        @Override
        public Object tryInvoke(String name, Object obj, Object... params) {
            if (!method.equals(name) || !isSupportedPolyfill(obj, name, params)) {
                return JexlEngine.TRY_FAILED;
            }
            return invoke(obj, params);
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
            return Object.class;
        }
    }

    /**
     * Safe stateless {@code JSON} helper exposed in the JEXL context.
     */
    public static final class JsJsonGlobal {

        private final ObjectMapper mapper;

        public JsJsonGlobal(ObjectMapper mapper) {
            this.mapper = Objects.requireNonNullElse(mapper, AiConfig.SHARED_OBJECT_MAPPER);
        }

        public String stringify(Object obj) throws JsonProcessingException {
            return mapper.writeValueAsString(obj);
        }

        public String stringify(Object obj, Object replacer) throws JsonProcessingException {
            return mapper.writeValueAsString(obj);
        }

        public String stringify(Object obj, Object replacer, Object indent) throws JsonProcessingException {
            if (shouldPrettyPrint(indent)) {
                return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
            }
            return mapper.writeValueAsString(obj);
        }

        public Object parse(String json) throws JsonProcessingException {
            if (json == null) {
                return null;
            }
            return mapper.readValue(json, Object.class);
        }

        private static boolean shouldPrettyPrint(Object indent) {
            if (indent instanceof Number n) {
                return n.intValue() > 0;
            }
            if (indent instanceof CharSequence cs) {
                return !cs.isEmpty();
            }
            return false;
        }
    }

    /**
     * Safe stateless {@code Object} helper exposed in the JEXL context.
     */
    public static final class JsObjectGlobal {

        public List<String> keys(Map<?, ?> map) {
            if (map == null || map.isEmpty()) {
                return new ArrayList<>();
            }
            List<String> result = new ArrayList<>(map.size());
            for (Object key : map.keySet()) {
                result.add(String.valueOf(key));
            }
            return result;
        }

        public List<Object> values(Map<?, ?> map) {
            if (map == null || map.isEmpty()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(map.values());
        }

        public List<List<Object>> entries(Map<?, ?> map) {
            if (map == null || map.isEmpty()) {
                return new ArrayList<>();
            }
            List<List<Object>> result = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                List<Object> pair = new ArrayList<>(2);
                pair.add(entry.getKey());
                pair.add(entry.getValue());
                result.add(pair);
            }
            return result;
        }
    }
}

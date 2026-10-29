package org.roxycode.app.ai;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.*;
import java.util.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;

public class LLMDocGenerator {

    private static final String DOC_END = " */\n";
    private static final String DOC_LINE_START = " * ";
    private static final String FENCE_START = "```typescript\n";
    private static final String FENCE_END = "```\n";

    public String generateDoc(Collection<Object> services) {
        return generateDoc(services, null);
    }

    public String generateDoc(Collection<Object> services, WorkflowPhase currentPhase) {
        StringBuilder sb = new StringBuilder("### JEXL API Documentation\n\n").append(FENCE_START)
                .append("""
                        /**
                         * ⚠️ INTEROP WARNING: executeJexl is a lightweight JavaScript-like orchestration layer over RoxyCode's Java services.
                         * Always return objects as the last expression of your script to inspect them.
                         * Note: Code must be written in Apache Commons JEXL 3 syntax.
                         */

                        """);
        Set<Class<?>> extraTypes = new LinkedHashSet<>();

        for (Object service : services) {
            if (service == null) {
                continue;
            }
            Class<?> clazz = AopUtils.getTargetClass(service);
            AgentService agentService = AnnotationUtils.findAnnotation(clazz, AgentService.class);
            if (agentService == null) {
                continue;
            }

            AgentDoc agentDoc = AnnotationUtils.findAnnotation(clazz, AgentDoc.class);
            if (agentDoc != null) {
                sb.append("/**\n").append(DOC_LINE_START).append(agentDoc.value()).append("\n");
                for (String note : agentDoc.notes()) {
                    sb.append(DOC_LINE_START).append("Note: ").append(note).append("\n");
                }
                sb.append(DOC_END);
            }
            sb.append("// Global variable exposed in JEXL: ").append(agentService.value()).append("\n");
            generateClassStub(clazz, currentPhase, sb, extraTypes);
            sb.append("\n");
        }

        if (!extraTypes.isEmpty()) {
            sb.append("// --- Supporting Types ---\n\n");
            Queue<Class<?>> queue = new ArrayDeque<>(extraTypes);
            Set<Class<?>> visited = new HashSet<>(extraTypes);
            while (!queue.isEmpty()) {
                Class<?> type = queue.poll();
                Set<Class<?>> discovered = new LinkedHashSet<>();
                generateTypeSchema(type, sb, discovered);
                sb.append("\n");
                for (Class<?> newType : discovered) {
                    if (visited.add(newType)) {
                        queue.add(newType);
                    }
                }
            }
        }
        return sb.append(FENCE_END).toString();
    }

    static boolean isMethodAllowedInPhase(AgentDoc doc, WorkflowPhase currentPhase) {
        if (doc == null || doc.phases().length == 0) {
            return false;
        }
        List<String> phaseList = Arrays.asList(doc.phases());
        return currentPhase == null || phaseList.contains("*") || phaseList.contains(currentPhase.name());
    }

    private void generateClassStub(Class<?> clazz, WorkflowPhase currentPhase, StringBuilder sb,
            Set<Class<?>> extraTypes) {
        sb.append("interface ").append(clazz.getSimpleName()).append(" {\n");
        Method[] methods = clazz.getMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName).thenComparingInt(Method::getParameterCount)
                .thenComparing(m -> Arrays.toString(m.getParameterTypes())));

        for (Method m : methods) {
            if (m.getDeclaringClass() == Object.class || m.isBridge() || m.isSynthetic()
                    || !Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            AgentDoc doc = AnnotationUtils.findAnnotation(m, AgentDoc.class);
            if (!isMethodAllowedInPhase(doc, currentPhase)) {
                continue;
            }

            sb.append("    /**\n     * ").append(doc.value()).append("\n");
            Parameter[] params = m.getParameters();
            for (Parameter p : params) {
                AgentDoc paramDoc = AnnotationUtils.findAnnotation(p, AgentDoc.class);
                if (paramDoc != null && !paramDoc.value().isBlank()) {
                    sb.append("     * @param ").append(p.getName()).append(" ").append(paramDoc.value()).append("\n");
                }
            }
            for (String example : doc.examples()) {
                sb.append("     * Example: ").append(example).append("\n");
            }
            sb.append("     */\n    ").append(m.getName()).append("(");

            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(params[i].getName()).append(": ")
                        .append(getMethodTypeName(params[i].getType(), params[i].getParameterizedType(), extraTypes));
            }
            sb.append("): ").append(getMethodTypeName(m.getReturnType(), m.getGenericReturnType(), extraTypes))
                    .append(";\n\n");
        }
        sb.append("}\n");
    }

    private String getMethodTypeName(Class<?> rawType, Type genericType, Set<Class<?>> extraTypes) {
        Set<Class<?>> genericExtraTypes = new LinkedHashSet<>();
        String genericName = getGenericTypeName(genericType != null ? genericType : rawType, genericExtraTypes);
        if (!genericExtraTypes.isEmpty()) {
            extraTypes.addAll(genericExtraTypes);
            return genericName;
        }
        return getTypeName(rawType, extraTypes);
    }

    private String getGenericTypeName(Type genericType, Set<Class<?>> extraTypes) {
        if (genericType == null) {
            throw new IllegalArgumentException("Type must not be null");
        }
        if (genericType instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            String rawName = raw instanceof Class<?> rawClass ? getTypeName(rawClass, extraTypes) : raw.getTypeName();
            Type[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length == 0) {
                return rawName;
            }
            StringBuilder sb = new StringBuilder(rawName).append("<");
            for (int i = 0; i < typeArgs.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(getGenericTypeArgumentName(typeArgs[i], extraTypes));
            }
            return sb.append(">").toString();
        }
        if (genericType instanceof GenericArrayType arrayType) {
            return getGenericTypeName(arrayType.getGenericComponentType(), extraTypes) + "[]";
        }
        if (genericType instanceof Class<?> clazz) {
            return getTypeName(clazz, extraTypes);
        }
        return genericType.getTypeName();
    }

    private String getGenericTypeArgumentName(Type argType, Set<Class<?>> extraTypes) {
        if (argType instanceof Class<?> clazz) {
            if (clazz.getName().startsWith("org.roxycode.app")) {
                extraTypes.add(clazz);
            }
            return clazz.getSimpleName();
        }
        if (argType instanceof WildcardType wt) {
            Type[] upper = wt.getUpperBounds();
            return (upper.length > 0 && upper[0] != Object.class)
                    ? getGenericTypeArgumentName(upper[0], extraTypes)
                    : "any";
        }
        return getGenericTypeName(argType, extraTypes);
    }

    private String getTypeName(Class<?> type, Set<Class<?>> extraTypes) {
        if (type.isArray()) {
            return getTypeName(type.getComponentType(), extraTypes) + "[]";
        }
        if (type == void.class || type == Void.class) {
            return "void";
        }
        if (type == String.class) {
            return "string";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (Number.class.isAssignableFrom(type) || type == int.class || type == long.class || type == double.class
                || type == float.class) {
            return "number";
        }
        if (type.getName().startsWith("org.roxycode.app")) {
            extraTypes.add(type);
        }
        return type.getSimpleName();
    }

    private void generateTypeSchema(Class<?> type, StringBuilder sb, Set<Class<?>> extraTypes) {
        if (type.isEnum()) {
            sb.append("enum ").append(type.getSimpleName()).append(" {\n    ");
            Object[] constants = type.getEnumConstants();
            for (int i = 0; i < constants.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(constants[i]);
            }
            sb.append("\n}\n");
        } else if (type.isRecord()) {
            sb.append(
                    "// Supporting Result Type: access fields as JS properties (obj.field) or method calls (obj.field())\ninterface ")
                    .append(type.getSimpleName()).append(" {\n");
            for (RecordComponent comp : type.getRecordComponents()) {
                sb.append("    ").append(comp.getName()).append(": ")
                        .append(getGenericTypeName(comp.getGenericType(), extraTypes)).append(";\n");
            }
            sb.append("}\n");
        } else {
            sb.append("interface ").append(type.getSimpleName()).append(" {\n");
            Map<String, String> properties = new TreeMap<>();
            Field[] fields = type.getDeclaredFields();
            Arrays.sort(fields, Comparator.comparing(Field::getName));
            for (Field f : fields) {
                if (Modifier.isPublic(f.getModifiers()) && !Modifier.isStatic(f.getModifiers())) {
                    properties.putIfAbsent(f.getName(), getGenericTypeName(f.getGenericType(), extraTypes));
                }
            }
            try {
                for (PropertyDescriptor pd : Introspector.getBeanInfo(type, Object.class).getPropertyDescriptors()) {
                    Method readMethod = pd.getReadMethod();
                    if (readMethod != null && pd.getPropertyType() != null) {
                        properties.putIfAbsent(pd.getName(),
                                getGenericTypeName(readMethod.getGenericReturnType(), extraTypes));
                    }
                }
            } catch (IntrospectionException ignored) {
            }
            properties.forEach((k, v) -> sb.append("    ").append(k).append(": ").append(v).append(";\n"));
            sb.append("}\n");
        }
    }
}

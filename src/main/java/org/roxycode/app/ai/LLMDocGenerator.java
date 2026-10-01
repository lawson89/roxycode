package org.roxycode.app.ai;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates LLM-friendly documentation for JEXL-exposed services.
 */
public class LLMDocGenerator {

    public String generateDoc(Collection<Object> services) {
        StringBuilder sb = new StringBuilder();
        sb.append("### JEXL API Documentation\n\n");
        sb.append("```java\n");
        sb.append("/**\n");
        sb.append(" * ⚠️ INTEROP WARNING: These services return Java objects in Apache Commons JEXL.\n");
        sb.append(" * Always return objects as the last expression of your script to inspect them.\n");
        sb.append(" * Note: Code must be written in Apache Commons JEXL 3 syntax.\n");
        sb.append(" */\n\n");

        Set<Class<?>> extraTypes = new LinkedHashSet<>();

        for (Object service : services) {
            Class<?> clazz = service.getClass();
            AgentService agentService = clazz.getAnnotation(AgentService.class);
            if (agentService == null) continue;

            AgentDoc agentDoc = clazz.getAnnotation(AgentDoc.class);
            if (agentDoc != null) {
                sb.append("/**\n");
                sb.append(" * ").append(agentDoc.value()).append("\n");
                for (String note : agentDoc.notes()) {
                    sb.append(" * Note: ").append(note).append("\n");
                }
                sb.append(" */\n");
            }
            
            sb.append("// Global variable exposed in JEXL: ").append(agentService.value()).append("\n");
            generateClassStub(clazz, sb, extraTypes);
            sb.append("\n");
        }

        if (!extraTypes.isEmpty()) {
            sb.append("// --- Supporting Types ---\n\n");
            for (Class<?> type : extraTypes) {
                generateTypeSchema(type, sb);
                sb.append("\n");
            }
        }

        sb.append("```\n");
        return sb.toString();
    }

    private void generateClassStub(Class<?> clazz, StringBuilder sb, Set<Class<?>> extraTypes) {
        String typeName = clazz.isInterface() ? "interface" : "class";
        sb.append("public ").append(typeName).append(" ").append(clazz.getSimpleName()).append(" {\n");
        
        Method[] methods = clazz.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        
        for (Method m : methods) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            
            AgentDoc doc = m.getAnnotation(AgentDoc.class);
            if (doc != null) {
                sb.append("    /**\n");
                sb.append("     * ").append(doc.value()).append("\n");
                for (String example : doc.examples()) {
                    sb.append("     * Example: ").append(example).append("\n");
                }
                sb.append("     */\n");
            }
            
            sb.append("    ").append(getTypeName(m.getReturnType(), extraTypes))
              .append(" ").append(m.getName()).append("(");
            
            Parameter[] params = m.getParameters();
            for (int i = 0; i < params.length; i++) {
                sb.append(getTypeName(params[i].getType(), extraTypes)).append(" ").append(params[i].getName());
                if (i < params.length - 1) sb.append(", ");
            }
            sb.append(");\n\n");
        }
        sb.append("}\n");
    }

    private String getTypeName(Class<?> type, Set<Class<?>> extraTypes) {
        if (type.isArray()) {
            return getTypeName(type.getComponentType(), extraTypes) + "[]";
        }
        String name = type.getSimpleName();
        if (type.isPrimitive() || type.getName().startsWith("java.lang") || type.getName().startsWith("java.util")) {
            return name;
        }
        if (type.getName().startsWith("org.roxycode.app")) {
            extraTypes.add(type);
            return name;
        }
        return name;
    }

    private void generateTypeSchema(Class<?> type, StringBuilder sb) {
        if (type.isEnum()) {
            sb.append("public enum ").append(type.getSimpleName()).append(" {\n");
            sb.append("    ").append(Arrays.stream(type.getEnumConstants())
              .map(Object::toString)
              .collect(Collectors.joining(", ")))
              .append("\n}\n");
        } else if (type.isRecord()) {
            sb.append("public record ").append(type.getSimpleName()).append("(\n");
            RecordComponent[] components = type.getRecordComponents();
            for (int i = 0; i < components.length; i++) {
                sb.append("    ").append(components[i].getType().getSimpleName()).append(" ").append(components[i].getName());
                if (i < components.length - 1) sb.append(",\n");
            }
            sb.append("\n) {}\n");
        } else {
            sb.append("public class ").append(type.getSimpleName()).append(" {\n");
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isPublic(f.getModifiers())) {
                    sb.append("    public ").append(f.getType().getSimpleName()).append(" ").append(f.getName()).append(";\n");
                }
            }
            sb.append("}\n");
        }
    }
}

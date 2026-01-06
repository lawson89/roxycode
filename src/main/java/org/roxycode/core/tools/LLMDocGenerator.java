package org.roxycode.core.tools;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

public class LLMDocGenerator {

    public static String generateApiDocs(Map<String, Object> services) {
        StringBuilder sb = new StringBuilder();

        for (Map.Entry<String, Object> entry : services.entrySet()) {
            String serviceName = entry.getKey();
            Object serviceObj = entry.getValue();
            Class<?> clazz = serviceObj.getClass();

            sb.append("// Service: ").append(serviceName).append("\n");
            sb.append("const ").append(serviceName).append(" = {\n");

            for (Method method : clazz.getMethods()) {
                // Skip standard Object methods
                if (method.getDeclaringClass().equals(Object.class)) continue;

                // 1. Add Method Description
                if (method.isAnnotationPresent(LLMDoc.class)) {
                    sb.append("  /** ").append(method.getAnnotation(LLMDoc.class).value()).append(" */\n");
                }

                sb.append("  ").append(method.getName()).append("(");

                // 2. Add Parameters with Descriptions
                Parameter[] params = method.getParameters();
                for (int i = 0; i < params.length; i++) {
                    Parameter p = params[i];

                    // Add param description inline if available
                    if (p.isAnnotationPresent(LLMDoc.class)) {
                        sb.append("/* ").append(p.getAnnotation(LLMDoc.class).value()).append(" */ ");
                    }

                    String type = mapJavaTypeToJs(p.getType().getSimpleName());
                    sb.append(p.getName()).append(": ").append(type);

                    if (i < params.length - 1) sb.append(", ");
                }

                String returnType = mapJavaTypeToJs(method.getReturnType().getSimpleName());
                sb.append("): ").append(returnType).append(",\n\n");
            }
            sb.append("};\n\n");
        }
        return sb.toString();
    }

    private static String mapJavaTypeToJs(String javaType) {
        return switch (javaType) {
            case "String" -> "string";
            case "int", "Integer", "long", "double" -> "number";
            case "boolean", "Boolean" -> "boolean";
            case "void" -> "void";
            case "Map" -> "object";
            case "List", "ArrayList" -> "Array<any>";
            default -> "any";
        };
    }
}

package org.roxycode.app.ai;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates LLM-friendly documentation for JEXL-exposed services. Uses pseudo-TypeScript syntax for
 * better compatibility with LLM reasoning.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class LLMDocGenerator {
  private static final String DOC_END = " */\n";
  private static final String DOC_LINE_START = " * ";

  public String generateDoc(Collection<Object> services) {
    StringBuilder sb = new StringBuilder();
    sb.append("### JEXL API Documentation\n\n");
    sb.append("```typescript\n");
    sb.append("/**\n");
    sb.append(
        " * ⚠️ INTEROP WARNING: These services return Java objects in Apache Commons JEXL.\n");
    sb.append(" * Always return objects as the last expression of your script to inspect them.\n");
    sb.append(" * Note: Code must be written in Apache Commons JEXL 3 syntax.\n");
    sb.append(DOC_END).append("\n");

    Set<Class<?>> extraTypes = new LinkedHashSet<>();

    for (Object service : services) {
      Class<?> clazz = service.getClass();
      AgentService agentService = clazz.getAnnotation(AgentService.class);
      if (agentService == null) continue;

      AgentDoc agentDoc = clazz.getAnnotation(AgentDoc.class);
      if (agentDoc != null) {
        sb.append("/**\n");
        sb.append(DOC_LINE_START).append(agentDoc.value()).append("\n");
        for (String note : agentDoc.notes()) {
          sb.append(DOC_LINE_START).append("Note: ").append(note).append("\n");
        }
        sb.append(DOC_END);
      }

      sb.append("// Global variable exposed in JEXL: ").append(agentService.value()).append("\n");
      generateClassStub(clazz, sb, extraTypes);
      sb.append("\n");
    }

    if (!extraTypes.isEmpty()) {
      sb.append("// --- Supporting Types ---\n\n");
      for (Class<?> type : extraTypes) {
        generateTypeSchema(type, sb, extraTypes);
        sb.append("\n");
      }
    }

    sb.append("```\n");
    return sb.toString();
  }

  private void generateClassStub(Class<?> clazz, StringBuilder sb, Set<Class<?>> extraTypes) {
    sb.append("interface ").append(clazz.getSimpleName()).append(" {\n");

    Method[] methods = clazz.getDeclaredMethods();
    Arrays.sort(methods, Comparator.comparing(Method::getName));

    for (Method m : methods) {
      if (!Modifier.isPublic(m.getModifiers())) continue;

      AgentDoc doc = m.getAnnotation(AgentDoc.class);
      if (doc == null) continue;
      if (doc != null) {
        sb.append("    /**\n");
        sb.append("     * ").append(doc.value()).append("\n");
        for (String example : doc.examples()) {
          sb.append("     * Example: ").append(example).append("\n");
        }
        sb.append("     */\n");
      }

      sb.append("    ").append(m.getName()).append("(");

      Parameter[] params = m.getParameters();
      boolean first = true;
      for (Parameter p : params) {
        if (!first) {
          sb.append(", ");
        }
        sb.append(p.getName()).append(": ").append(getTypeName(p.getType(), extraTypes));
        first = false;
      }
      sb.append("): ").append(getTypeName(m.getReturnType(), extraTypes)).append(";\n\n");
    }
    sb.append("}\n");
  }

  private String getTypeName(Class<?> type, Set<Class<?>> extraTypes) {
    if (type.isArray()) {
      return getTypeName(type.getComponentType(), extraTypes) + "[]";
    }
    if (type == void.class || type == Void.class) return "void";
    if (type == String.class) return "string";
    if (type == boolean.class || type == Boolean.class) return "boolean";
    if (type == int.class
        || type == Integer.class
        || type == long.class
        || type == Long.class
        || type == double.class
        || type == Double.class
        || type == float.class
        || type == Float.class) return "number";

    String name = type.getSimpleName();
    if (type.getName().startsWith("java.lang") || type.getName().startsWith("java.util")) {
      return name;
    }
    if (type.getName().startsWith("org.roxycode.app")) {
      extraTypes.add(type);
      return name;
    }
    return name;
  }

  private void generateTypeSchema(Class<?> type, StringBuilder sb, Set<Class<?>> extraTypes) {
    if (type.isEnum()) {
      sb.append("enum ").append(type.getSimpleName()).append(" {\n");
      sb.append("    ")
          .append(
              Arrays.stream(type.getEnumConstants())
                  .map(Object::toString)
                  .collect(Collectors.joining(", ")))
          .append("\n}\n");
    } else if (type.isRecord()) {
      sb.append("interface ").append(type.getSimpleName()).append(" {\n");
      for (RecordComponent comp : type.getRecordComponents()) {
        sb.append("    ")
            .append(comp.getName())
            .append(": ")
            .append(getTypeName(comp.getType(), extraTypes))
            .append(";\n");
      }
      sb.append("}\n");
    } else {
      sb.append("interface ").append(type.getSimpleName()).append(" {\n");
      for (Field f : type.getDeclaredFields()) {
        if (Modifier.isPublic(f.getModifiers())) {
          sb.append("    ")
              .append(f.getName())
              .append(": ")
              .append(getTypeName(f.getType(), extraTypes))
              .append(";\n");
        }
      }
      for (Method m : type.getDeclaredMethods()) {
        if (Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())) {
          String name = m.getName();
          if (m.getParameterCount() == 0 && m.getReturnType() != void.class) {
            String propName = null;
            if (name.startsWith("get") && name.length() > 3) {
              propName = decapitalize(name, 3);
            } else if (name.startsWith("is")
                && name.length() > 2
                && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
              propName = decapitalize(name, 2);
            } else if (name.startsWith("has")
                && name.length() > 3
                && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
              propName = decapitalize(name, 3);
            }
            if (propName != null) {
              sb.append("    ")
                  .append(propName)
                  .append(": ")
                  .append(getTypeName(m.getReturnType(), extraTypes))
                  .append(";\n");
            }
          }
        }
      }
      sb.append("}\n");
    }
  }

  private String decapitalize(String name, int offset) {
    return Character.toLowerCase(name.charAt(offset)) + name.substring(offset + 1);
  }
}

package org.roxycode.app.ai;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.springframework.core.annotation.AnnotationUtils;

public final class AgentJexlPermissions extends JexlPermissions.Delegate {

    private static final String APP_PACKAGE_PREFIX = "org.roxycode.app.";
    private static final String APP_WILDCARD_PERMISSION = "org.roxycode.app.*";
    private static final String GET_CLASS_METHOD = "getClass";
    private static final Set<Class<?>> SAFE_JDK_TYPES = Set.of(String.class, CharSequence.class, Number.class,
            Boolean.class, Character.class, Enum.class, Math.class, Iterable.class, Collection.class, List.class,
            Map.class, Set.class, Iterator.class, Map.Entry.class);

    public static final AgentJexlPermissions INSTANCE = new AgentJexlPermissions();

    public AgentJexlPermissions() {
        this(JexlPermissions.RESTRICTED.compose(APP_WILDCARD_PERMISSION));
    }

    public AgentJexlPermissions(JexlPermissions delegate) {
        super(delegate);
    }

    @Override
    public boolean allow(Constructor<?> ctor) {
        return false;
    }

    @Override
    public boolean allow(Method method) {
        return method != null && allow(method.getDeclaringClass(), method);
    }

    @Override
    public boolean allow(Class<?> clazz, Method method) {
        if (clazz == null || method == null) {
            return false;
        }
        if (GET_CLASS_METHOD.equals(method.getName()) && method.getParameterCount() == 0) {
            return false;
        }
        if (method.getDeclaringClass() == Object.class) {
            return super.allow(clazz, method);
        }
        if (isAgentServiceClass(clazz) || isAgentServiceClass(method.getDeclaringClass())) {
            return hasAgentDoc(method) && super.allow(clazz, method);
        }
        if (isAllowedNonServiceType(clazz) && isAllowedNonServiceType(method.getDeclaringClass())) {
            return super.allow(clazz, method);
        }
        return false;
    }

    static boolean isAllowedNonServiceType(Class<?> type) {
        if (type == null) {
            return false;
        }
        if (type == Object.class) {
            return true;
        }
        if (type.getName().startsWith(APP_PACKAGE_PREFIX)) {
            return !isAgentServiceClass(type);
        }
        for (Class<?> safeType : SAFE_JDK_TYPES) {
            if (safeType.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    static boolean isAgentServiceClass(Class<?> clazz) {
        return clazz != null && AnnotationUtils.findAnnotation(clazz, AgentService.class) != null;
    }

    static boolean hasAgentDoc(Method method) {
        return method != null && AnnotationUtils.findAnnotation(method, AgentDoc.class) != null;
    }
}

package org.roxycode.app.ai;

import java.lang.reflect.Method;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.springframework.core.annotation.AnnotationUtils;

/**
 * JEXL permission delegate that restricts method calls on {@link AgentService} beans to only those
 * methods annotated with {@link AgentDoc} (plus standard {@link Object} methods).
 */
public final class AgentJexlPermissions extends JexlPermissions.Delegate {

    public static final AgentJexlPermissions INSTANCE = new AgentJexlPermissions();

    public AgentJexlPermissions() {
        this(JexlPermissions.UNRESTRICTED);
    }

    public AgentJexlPermissions(JexlPermissions delegate) {
        super(delegate);
    }

    @Override
    public boolean allow(Method method) {
        if (method == null) {
            return false;
        }
        return allow(method.getDeclaringClass(), method);
    }

    @Override
    public boolean allow(Class<?> clazz, Method method) {
        if (clazz == null || method == null) {
            return false;
        }
        if (method.getDeclaringClass() == Object.class) {
            return super.allow(clazz, method);
        }
        if (isAgentServiceClass(clazz) || isAgentServiceClass(method.getDeclaringClass())) {
            return hasAgentDoc(method) && super.allow(clazz, method);
        }
        return super.allow(clazz, method);
    }

    static boolean isAgentServiceClass(Class<?> clazz) {
        return clazz != null && AnnotationUtils.findAnnotation(clazz, AgentService.class) != null;
    }

    static boolean hasAgentDoc(Method method) {
        return method != null && AnnotationUtils.findAnnotation(method, AgentDoc.class) != null;
    }
}

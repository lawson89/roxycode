package org.roxycode.app.ai;

import org.springframework.stereotype.Service;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark Spring services for exposure to the AI Agent via JEXL.
 * This annotation is meta-annotated with {@link Service} to ensure Spring bean discovery.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Service
public @interface AgentService {
    /**
     * The name of the variable as it will be exposed in the JEXL context.
     * @return the JEXL variable name
     */
    String value();

    /**
     * The roles allowed to access this service.
     * Defaults to an empty array, meaning no roles are allowed.
     * Use "*" as a wildcard to allow access to all agent roles.
     * @return the allowed roles
     */
    String[] roles() default {};
}

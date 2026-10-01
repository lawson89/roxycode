package org.roxycode.app.ai;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Provides rich documentation for AI agents about JEXL-exposed components.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface AgentDoc {
    /**
     * A description of the component.
     * @return the description
     */
    String value();

    /**
     * Usage examples for the component.
     * @return the examples
     */
    String[] examples() default {};

    /**
     * Additional notes about the component.
     * @return the notes
     */
    String[] notes() default {};
}

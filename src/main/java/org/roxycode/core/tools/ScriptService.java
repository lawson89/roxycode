package org.roxycode.core.tools;

import jakarta.inject.Singleton;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE) // Target classes, not fields
@Singleton // Automatically make annotated classes Singletons
public @interface ScriptService {
    /** The variable name to use in the JS context (e.g. "fs", "git") */
    String value();
}
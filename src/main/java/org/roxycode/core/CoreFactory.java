package org.roxycode.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.nio.file.Paths;

@Factory
public class CoreFactory {

    @Singleton
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Singleton
    public Sandbox sandbox() {
        // Initialize Sandbox with the current working directory
        return new Sandbox(Paths.get("."));
    }
}
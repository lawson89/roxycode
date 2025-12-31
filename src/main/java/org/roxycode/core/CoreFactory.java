package org.roxycode.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

@Factory
public class CoreFactory {

    @Singleton
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

}
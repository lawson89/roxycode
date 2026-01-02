package org.roxycode.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

@Factory
public class CoreFactory {

    @Singleton
    @Named("json")
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Singleton
    @Named("toml")
    public ObjectMapper tomlMapper() {
        return new ObjectMapper(new TomlFactory());
    }

}
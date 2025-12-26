package org.roxycode;

import io.micronaut.runtime.Micronaut;

public class Application {

    public static void main(String[] args) {
        // Just start the context.
        // CommandLineRunner listens for the StartupEvent and will run automatically.
        Micronaut.run(Application.class, args);
    }
}
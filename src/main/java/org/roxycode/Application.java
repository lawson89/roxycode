package org.roxycode;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;
import org.roxycode.core.CommandLineRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public class Application {
    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        // Check for --cli flag manually to determine execution path
        boolean cliMode = Arrays.asList(args).contains("--cli");

        if (cliMode) {
            try (ApplicationContext context = Micronaut.run(Application.class, args)) {
                // Run the stubbed CommandLineRunner bean
                context.getBean(CommandLineRunner.class).run();
            }
        } else {
            // Log warning if flag is missing [cite: 48]
            LOG.info("GUI mode not ready");
        }
    }
}
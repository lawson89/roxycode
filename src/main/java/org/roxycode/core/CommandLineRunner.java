package org.roxycode.core;

import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

@Singleton
public class CommandLineRunner implements ApplicationEventListener<StartupEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(CommandLineRunner.class);

    private final GenAIService genAIService;
    private final Environment environment;

    @Inject
    public CommandLineRunner(GenAIService genAIService, Environment environment) {
        this.genAIService = genAIService;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        LOG.info("🚀 CommandLineRunner started.");

        Optional<String> promptOpt = environment.getProperty("prompt", String.class);

        // Default to "." if not provided.
        // When running via Maven, user should pass --root=".."
        String rootPath = environment.getProperty("root", String.class, ".");

        if (promptOpt.isPresent()) {
            String prompt = promptOpt.get();
            LOG.info("🤖 Prompt detected: {}", prompt);
            LOG.info("📂 Project Root set to: {}", rootPath);

            try {
                // Pass rootPath to the chat method
                String response = genAIService.chat(prompt, rootPath);
                LOG.info("🏁 Final AI Response:\n{}", response);
            } catch (Exception e) {
                LOG.error("❌ Error during AI execution", e);
            }
        } else {
            if (environment.containsProperty("cli")) {
                LOG.error("❌ --cli flag detected but no --prompt provided!");
            } else {
                LOG.info("ℹ️ No --prompt found. Standing by.");
            }
        }
    }
}
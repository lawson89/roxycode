package org.roxycode;

import io.micronaut.runtime.Micronaut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class Application {
    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        String userDir = System.getProperty("user.dir");
        LOG.info("Application starting in: " + userDir);

        // Check for .env in current directory or parent
        File envFile = new File(".env");
        if (!envFile.exists()) {
            envFile = new File("../.env");
        }

        if (envFile.exists()) {
            LOG.info("Loading .env from: " + envFile.getAbsolutePath());
            try {
                List<String> lines = Files.readAllLines(envFile.toPath());
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    
                    int sep = line.indexOf('=');
                    if (sep > 0) {
                        String key = line.substring(0, sep).trim();
                        String value = line.substring(sep + 1).trim();
                        
                        // Handle potential quotes manually using char check
                        if (value.length() >= 2) {
                            char first = value.charAt(0);
                            char last = value.charAt(value.length() - 1);
                            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                                value = value.substring(1, value.length() - 1);
                            }
                        }
                        
                        System.setProperty(key, value);
                        if (key.equals("GEMINI_API_KEY")) {
                            LOG.info("GEMINI_API_KEY detected and registered.");
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Failed to parse .env: " + e.getMessage());
            }
        } else {
            LOG.warn("No .env file found in current or parent directory!");
        }

        Micronaut.run(Application.class, args);
    }
}

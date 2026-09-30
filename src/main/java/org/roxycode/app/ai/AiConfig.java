package org.roxycode.app.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for AI-related beans.
 */
@Configuration
public class AiConfig {

    @Bean
    public JexlTool jexlTool() {
        return new JexlTool();
    }
}

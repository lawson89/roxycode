package org.roxycode.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;

/**
 * Configuration for AI-related beans.
 */
@Configuration
public class AiConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public TomlMapper tomlMapper() {
        return new TomlMapper();
    }

    @Bean
    public JexlTool jexlTool(JexlServiceRegistry registry) {
        return new JexlTool(registry);
    }

    @Bean
    public JexlServiceRegistry jexlServiceRegistry(ApplicationContext context) {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        Map<String, Object> beans = context.getBeansWithAnnotation(AgentService.class);
        for (Object bean : beans.values()) {
            registry.register(bean);
        }
        return registry;
    }
}
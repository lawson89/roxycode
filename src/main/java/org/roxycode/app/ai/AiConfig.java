package org.roxycode.app.ai;

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
    public JexlTool jexlTool() {
        return new JexlTool();
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
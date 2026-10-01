package org.roxycode.app.ai;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import java.util.Map;
import java.util.function.Function;

/**
 * Configuration for AI-related beans.
 */
@Configuration
public class AiConfig {

    @Bean
    public JexlTool jexlTool() {
        return new JexlTool();
    }

    /**
     * Request record for JEXL execution.
     */
    public record JexlRequest(String script) {}

    /**
     * Response record for JEXL execution.
     */
    public record JexlResponse(String result) {}

    /**
     * Function bean for JEXL execution, used by Spring AI tool calling.
     */
    @Bean
    @Description("Executes a Jexl script and returns the result as a string. Useful for calculations and logic.")
    public Function<JexlRequest, JexlResponse> executeJexl(JexlTool jexlTool) {
        return request -> new JexlResponse(jexlTool.executeJexl(request.script()));
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
package org.roxycode.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Configuration for AI-related beans. */
@Configuration
public class AiConfig {

    public static final ObjectMapper SHARED_OBJECT_MAPPER =
            (ObjectMapper) new ObjectMapper().findAndRegisterModules();

    public static final TomlMapper SHARED_TOML_MAPPER =
            (TomlMapper) new TomlMapper().findAndRegisterModules();

    @Bean
    public ObjectMapper objectMapper() {
        return SHARED_OBJECT_MAPPER;
    }

    @Bean
    public TomlMapper tomlMapper() {
        return SHARED_TOML_MAPPER;
    }

    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return new RoxyToolExecutionExceptionProcessor();
    }

    @Bean
    @Primary
    public ToolCallingManager toolCallingManager(
            ToolExecutionExceptionProcessor toolExecutionExceptionProcessor,
            SettingsService settingsService,
            PlanService planService,
            ApplicationEventPublisher eventPublisher) {
        ToolCallingManager delegate =
                ToolCallingManager.builder()
                        .toolExecutionExceptionProcessor(toolExecutionExceptionProcessor)
                        .build();
        return new ReturnDirectToolCallingManager(
                delegate, settingsService, planService, eventPublisher);
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

    @Bean(destroyMethod = "close")
    public ExecutorService chatTurnExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

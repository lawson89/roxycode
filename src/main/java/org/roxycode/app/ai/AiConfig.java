package org.roxycode.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
  public JexlServiceRegistry jexlServiceRegistry(ApplicationContext context) {
    JexlServiceRegistry registry = new JexlServiceRegistry();
    Map<String, Object> beans = context.getBeansWithAnnotation(AgentService.class);
    for (Object bean : beans.values()) {
      registry.register(bean);
    }
    return registry;
  }
}

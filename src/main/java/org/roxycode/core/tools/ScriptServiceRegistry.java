package org.roxycode.core.tools;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.GenAIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct; // or jakarta.annotation.PostConstruct
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Collection;

@Singleton
public class ScriptServiceRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ScriptServiceRegistry.class);

    private final Map<String, Object> serviceMap = new HashMap<>();
    private String cachedApiDocs = "";

    @Inject
    ApplicationContext applicationContext;

    @PostConstruct
    void init() {
        try {
            // 1. Find all bean definitions with our annotation
            Collection<BeanDefinition<Object>> definitions =
                    applicationContext.getBeanDefinitions(Object.class);

            for (BeanDefinition<Object> definition : definitions) {
                if (definition.hasAnnotation(ScriptService.class)) {
                    // 2. Extract the name (e.g. "fs")
                    String jsName = definition.stringValue(ScriptService.class).orElse(null);

                    if (jsName != null) {
                        // 3. Get the actual instance from Micronaut
                        Object instance = applicationContext.getBean(definition);
                        LOG.info("Registered script service: {} -> {}", jsName, instance.getClass().getName());
                        serviceMap.put(jsName, instance);
                    }
                }
            }
        } catch (Exception e) {
           LOG.warn("Failed to register script service", e);
        }

        try {
            // 4. Generate the API docs once (using the generator from previous steps)
            this.cachedApiDocs = LLMDocGenerator.generateApiDocs(serviceMap);
        }catch (Exception e) {
            LOG.warn("Failed to generate api docs", e);
        }
    }

    public Map<String, Object> getServices() {
        return Collections.unmodifiableMap(serviceMap);
    }

    public String getApiDocs() {
        return cachedApiDocs;
    }
}

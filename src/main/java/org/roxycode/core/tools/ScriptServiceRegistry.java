package org.roxycode.core.tools;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Collection;

@Singleton
public class ScriptServiceRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ScriptServiceRegistry.class);

    private final Map<String, Object> serviceMap = new HashMap<>();
    private String cachedApiDocs = "";
    private boolean initialized = false;

    @Inject
    ApplicationContext applicationContext;

    private synchronized void ensureInitialized() {
        if (initialized) return;
        try {
            LOG.info("Initializing ScriptServiceRegistry...");
            Collection<BeanDefinition<Object>> definitions =
                    applicationContext.getBeanDefinitions(Object.class);

            for (BeanDefinition<Object> definition : definitions) {
                if (definition.hasAnnotation(ScriptService.class)) {
                    String jsName = definition.stringValue(ScriptService.class).orElse(null);
                    if (jsName != null) {
                        try {
                            Object instance = applicationContext.getBean(definition);
                            LOG.info("Registered script service: {} -> {}", jsName, instance.getClass().getName());
                            serviceMap.put(jsName, instance);
                        } catch (Exception e) {
                            LOG.warn("Failed to register script service: " + jsName, e);
                        }
                    }
                }
            }
            this.cachedApiDocs = LLMDocGenerator.generateApiDocs(serviceMap);
        } catch (Exception e) {
           LOG.warn("Failed to initialize ScriptServiceRegistry", e);
        }
        initialized = true;
    }

    public Map<String, Object> getServices() {
        ensureInitialized();
        return Collections.unmodifiableMap(serviceMap);
    }

    public String getApiDocs() {
        ensureInitialized();
        return "/** IMPORTANT! The following globals are exposed to the javascript sandbox */\n\n"  + cachedApiDocs;
    }
}

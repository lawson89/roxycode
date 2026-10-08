package org.roxycode.app.ai;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for JEXL-exposed services.
 */
public class JexlServiceRegistry {
    private final Map<String, Object> services = new LinkedHashMap<>();
    private final LLMDocGenerator docGenerator = new LLMDocGenerator();
    private final Map<String, String> docCache = new ConcurrentHashMap<>();

    public void register(Object service) {
        AgentService ann = service.getClass().getAnnotation(AgentService.class);
        if (ann != null) {
            services.put(ann.value(), service);
            docCache.clear();
        }
    }

    public Map<String, Object> getServices() {
        return Collections.unmodifiableMap(services);
    }

    public String getDocumentation(org.roxycode.app.ai.AgentRole currentRole) {
        String cacheKey = currentRole != null ? currentRole.name() : "NULL_ROLE";
        
        return docCache.computeIfAbsent(cacheKey, key -> {
            java.util.List<Object> allowedServices = new java.util.ArrayList<>();
            for (Object service : services.values()) {
                AgentService ann = service.getClass().getAnnotation(AgentService.class);
                if (ann != null) {
                    String[] roles = ann.roles();
                    
                    if (roles.length == 0) {
                        allowedServices.add(service);
                        continue;
                    }

                    java.util.List<String> roleList = java.util.Arrays.asList(roles);
                    boolean allowed = currentRole == null 
                            || roleList.contains("*") 
                            || roleList.contains(currentRole.name());

                    if (allowed) {
                        allowedServices.add(service);
                    }
                }
            }
            return docGenerator.generateDoc(allowedServices);
        });
    }
}
package org.roxycode.app.ai;

import java.util.*;

/**
 * Registry for JEXL-exposed services.
 */
public class JexlServiceRegistry {
    private final Map<String, Object> services = new LinkedHashMap<>();
    private final LLMDocGenerator docGenerator = new LLMDocGenerator();

    public void register(Object service) {
        AgentService ann = service.getClass().getAnnotation(AgentService.class);
        if (ann != null) {
            services.put(ann.value(), service);
        }
    }

    public Map<String, Object> getServices() {
        return Collections.unmodifiableMap(services);
    }

    public String getDocumentation(org.roxycode.app.ai.AgentRole currentRole) {
        java.util.List<Object> allowedServices = new java.util.ArrayList<>();
        for (Object service : services.values()) {
            AgentService ann = service.getClass().getAnnotation(AgentService.class);
            if (ann != null) {
                String[] roles = ann.roles();
                
                                boolean allowed = currentRole == null || roles.length == 0 || java.util.Arrays.asList(roles).contains("*") || java.util.Arrays.asList(roles).contains(currentRole.name());

                if (allowed) {
                    allowedServices.add(service);
                }
            }
        }
        return docGenerator.generateDoc(allowedServices);
    }
}
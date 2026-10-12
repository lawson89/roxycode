package org.roxycode.app.ai;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.roxycode.app.ai.services.FileEditorService;

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

    public Map<String, Object> getServices(WorkflowPhase phase) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : services.entrySet()) {
            Object service = entry.getValue();
            Class<?> clazz = service.getClass();
            AgentService ann = clazz.getAnnotation(AgentService.class);
            
            if (ann != null) {
                // Hard gate: FileEditorService (and any other mutation service) is restricted to CODE phase
                if (service instanceof FileEditorService) {
                    if (phase != WorkflowPhase.CODE) {
                        continue;
                    }
                }

                String[] phases = ann.phases();
                if (phases.length == 0) {
                    filtered.put(entry.getKey(), service);
                    continue;
                }

                List<String> phaseList = Arrays.asList(phases);
                boolean allowed = phase == null 
                        || phaseList.contains("*") 
                        || phaseList.contains(phase.name());

                if (allowed) {
                    filtered.put(entry.getKey(), service);
                }
            }
        }
        return filtered;
    }

    public String getDocumentation(WorkflowPhase currentPhase) {
        String cacheKey = currentPhase != null ? currentPhase.name() : "NULL_PHASE";
        
        return docCache.computeIfAbsent(cacheKey, key -> {
            Map<String, Object> filtered = getServices(currentPhase);
            return docGenerator.generateDoc(new ArrayList<>(filtered.values()));
        });
    }
}
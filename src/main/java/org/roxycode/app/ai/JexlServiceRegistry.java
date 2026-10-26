package org.roxycode.app.ai;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;

/** Registry for JEXL-exposed services. */
public class JexlServiceRegistry {
    private final Map<String, Object> services = Collections.synchronizedMap(new LinkedHashMap<>());
    private final LLMDocGenerator docGenerator = new LLMDocGenerator();
    private final Map<String, String> docCache = new ConcurrentHashMap<>();

    public synchronized void register(Object service) {
        if (service == null) {
            return;
        }
        Class<?> targetClass = AopUtils.getTargetClass(service);
        AgentService ann = AnnotationUtils.findAnnotation(targetClass, AgentService.class);
        if (ann != null) {
            services.put(ann.value(), service);
            docCache.clear();
        }
    }

    public synchronized Map<String, Object> getServices(WorkflowPhase phase) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : services.entrySet()) {
            Object service = entry.getValue();
            Class<?> targetClass = AopUtils.getTargetClass(service);
            AgentService ann = AnnotationUtils.findAnnotation(targetClass, AgentService.class);

            if (ann != null) {
                String[] phases = ann.phases();
                if (phases.length == 0) {
                    continue;
                }

                List<String> phaseList = Arrays.asList(phases);
                boolean allowed =
                        phase == null
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

        return docCache.computeIfAbsent(
                cacheKey,
                key -> {
                    Map<String, Object> filtered = getServices(currentPhase);
                    return docGenerator.generateDoc(new ArrayList<>(filtered.values()));
                });
    }
}

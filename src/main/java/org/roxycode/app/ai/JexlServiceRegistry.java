package org.roxycode.app.ai;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;

public class JexlServiceRegistry {
    private final Map<String, Object> services = Collections.synchronizedMap(new LinkedHashMap<>());
    private final LLMDocGenerator docGenerator = new LLMDocGenerator();
    private final Map<String, String> docCache = new ConcurrentHashMap<>();

    public synchronized void register(Object service) {
        if (service == null) return;
        AgentService ann =
                AnnotationUtils.findAnnotation(
                        AopUtils.getTargetClass(service), AgentService.class);
        if (ann != null) {
            services.put(ann.value(), service);
            docCache.clear();
        }
    }

    public synchronized Map<String, Object> getServices(WorkflowPhase phase) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : services.entrySet()) {
            Object service = entry.getValue();
            AgentService ann =
                    AnnotationUtils.findAnnotation(
                            AopUtils.getTargetClass(service), AgentService.class);
            if (ann != null && ann.phases().length > 0) {
                List<String> phaseList = Arrays.asList(ann.phases());
                if (phase == null || phaseList.contains("*") || phaseList.contains(phase.name())) {
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
                key ->
                        docGenerator.generateDoc(
                                new ArrayList<>(getServices(currentPhase).values())));
    }
}

package org.roxycode.app.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;

public class JexlServiceRegistry {
    private final Map<String, Object> services = Collections.synchronizedMap(new LinkedHashMap<>());
    private final LLMDocGenerator docGenerator = new LLMDocGenerator();
    private final Cache<String, String> docCache = Caffeine.newBuilder().maximumSize(16).build();

    public synchronized void register(Object service) {
        if (service == null)
            return;
        AgentService ann = AnnotationUtils.findAnnotation(AopUtils.getTargetClass(service), AgentService.class);
        if (ann != null) {
            services.put(ann.value(), service);
            docCache.invalidateAll();
        }
    }

    public synchronized Map<String, Object> getServices(WorkflowPhase phase) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : services.entrySet()) {
            Object service = entry.getValue();
            AgentService ann = AnnotationUtils.findAnnotation(AopUtils.getTargetClass(service), AgentService.class);
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
        return docCache.get(cacheKey,
                key -> docGenerator.generateDoc(new ArrayList<>(getServices(currentPhase).values()), currentPhase));
    }
}

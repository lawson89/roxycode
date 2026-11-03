package org.roxycode.app.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.*;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.UserInputService;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.service.SystemToolService;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;

public class JexlServiceRegistry {
    static final String GIT_SERVICE_NAME = "gitService";

    private final Map<String, Object> services = Collections.synchronizedMap(new LinkedHashMap<>());
    private final LLMDocGenerator docGenerator = new LLMDocGenerator();
    private final Cache<String, String> docCache = Caffeine.newBuilder().maximumSize(16).build();
    private final SystemToolService systemToolService;
    private Boolean gitAvailable;

    public JexlServiceRegistry() {
        this(null);
    }

    public JexlServiceRegistry(@Nullable SystemToolService systemToolService) {
        this.systemToolService = systemToolService;
    }

    public synchronized void register(Object service) {
        if (service == null) {
            return;
        }
        AgentService ann = AnnotationUtils.findAnnotation(AopUtils.getTargetClass(service), AgentService.class);
        if (ann != null) {
            services.put(ann.value(), service);
            docCache.invalidateAll();
        }
    }

    @EventListener
    public synchronized void onSystemToolsUpdated(SystemToolsUpdatedEvent event) {
        if (event == null) {
            return;
        }
        boolean available = SystemToolService.isGitDetected(event.tools());
        if (!Objects.equals(gitAvailable, available)) {
            gitAvailable = available;
            docCache.invalidateAll();
        }
    }

    public synchronized boolean isGitAvailable() {
        if (gitAvailable != null) {
            return gitAvailable;
        }
        if (systemToolService == null) {
            gitAvailable = true;
            return true;
        }
        boolean resolved = systemToolService.checkGitAvailable();
        gitAvailable = resolved;
        return resolved;
    }

    private boolean isServiceAvailable(String serviceName, Object service) {
        if (GIT_SERVICE_NAME.equals(serviceName) || service instanceof GitService) {
            return isGitAvailable();
        }
        return true;
    }

    public synchronized Map<String, Object> getServices(WorkflowPhase phase) {
        return getServices(phase, TurnToolContext.DEFAULT_CONVERSATION_ID);
    }

    public synchronized Map<String, Object> getServices(WorkflowPhase phase, String conversationId) {
        String resolvedConvId = StringUtils.defaultIfBlank(conversationId, TurnToolContext.DEFAULT_CONVERSATION_ID);
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : services.entrySet()) {
            Object service = entry.getValue();
            if (!isServiceAvailable(entry.getKey(), service)) {
                continue;
            }
            AgentService ann = AnnotationUtils.findAnnotation(AopUtils.getTargetClass(service), AgentService.class);
            if (ann != null && WorkflowPhase.isAllowed(ann.phases(), phase)) {
                Object boundService = service;
                if (service instanceof UserInputService userInputService) {
                    boundService = userInputService.forConversation(resolvedConvId);
                }
                filtered.put(entry.getKey(), boundService);
            }
        }
        return filtered;
    }

    public String getDocumentation(WorkflowPhase currentPhase) {
        isGitAvailable();
        String cacheKey = "NULL_PHASE";
        if (currentPhase != null) {
            cacheKey = currentPhase.name();
        }
        return docCache.get(cacheKey,
                key -> docGenerator.generateDoc(new ArrayList<>(getServices(currentPhase).values()), currentPhase));
    }
}

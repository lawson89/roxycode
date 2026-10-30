package org.roxycode.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import com.google.genai.types.HttpOptions;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;

/** Configuration for AI-related beans. */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    public static final int DEFAULT_HTTP_TIMEOUT_MS = 120_000;
    public static final int DEFAULT_TURN_TIMEOUT_SECONDS = 600;
    public static final int DEFAULT_MAX_TOOL_CALLS = 100;
    public static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(1500);
    public static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(15);

    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;
    private static final int HTTP_SERVER_ERROR_UPPER = 600;

    private static final List<String> NON_RETRYABLE_MARKERS = List.of("400 BAD REQUEST", "401 UNAUTHORIZED",
            "403 FORBIDDEN", "404 NOT FOUND", "INVALID_ARGUMENT", "PERMISSION_DENIED", "UNAUTHENTICATED",
            "API_KEY_INVALID");

    private static final List<String> TRANSIENT_STATUS_MARKERS = List.of("429", "RESOURCE_EXHAUSTED",
            "TOO MANY REQUESTS", "RATE LIMIT", "500 INTERNAL", "502 BAD GATEWAY", "503 SERVICE UNAVAILABLE",
            "504 GATEWAY TIMEOUT", "UNAVAILABLE", "DEADLINE_EXCEEDED", "SOCKET TIMEOUT", "READ TIMED OUT",
            "CONNECTION RESET");

    public static final ObjectMapper SHARED_OBJECT_MAPPER = (ObjectMapper) new ObjectMapper().findAndRegisterModules();

    public static final TomlMapper SHARED_TOML_MAPPER = (TomlMapper) new TomlMapper().findAndRegisterModules();

    @Bean
    public ObjectMapper objectMapper() {
        return SHARED_OBJECT_MAPPER;
    }

    @Bean
    public TomlMapper tomlMapper() {
        return SHARED_TOML_MAPPER;
    }

    @Bean(destroyMethod = "close")
    public Client googleGenAiClient(GoogleGenAiConnectionProperties properties,
            @Value("${roxycode.ai.http-timeout-ms:120000}") int httpTimeoutMs) throws IOException {
        Client.Builder builder = Client.builder();
        if (httpTimeoutMs > 0) {
            builder.httpOptions(HttpOptions.builder().timeout(httpTimeoutMs).build());
        }

        boolean hasApiKey = StringUtils.hasText(properties.getApiKey());
        boolean hasVertexConfig = StringUtils.hasText(properties.getProjectId())
                && StringUtils.hasText(properties.getLocation());

        if (properties.isVertexAi()) {
            if (!hasVertexConfig) {
                throw new IllegalStateException(
                        "Vertex AI mode requires both 'project-id' and 'location' to be configured.");
            }
            configureVertexAi(builder, properties);
        } else if (hasApiKey) {
            builder.apiKey(properties.getApiKey());
        } else if (hasVertexConfig) {
            configureVertexAi(builder, properties);
        } else {
            throw new IllegalStateException(
                    "Incomplete Google GenAI configuration: Provide 'api-key' for Gemini API or 'project-id' and 'location' for Vertex AI.");
        }
        return builder.build();
    }

    private static void configureVertexAi(Client.Builder builder, GoogleGenAiConnectionProperties props)
            throws IOException {
        Assert.hasText(props.getProjectId(), "Google GenAI project-id must be set for Vertex AI mode!");
        Assert.hasText(props.getLocation(), "Google GenAI location must be set for Vertex AI mode!");
        builder.project(props.getProjectId()).location(props.getLocation()).vertexAI(true);
        if (props.getCredentialsUri() != null) {
            try (InputStream is = props.getCredentialsUri().getInputStream()) {
                builder.credentials(GoogleCredentials.fromStream(is));
            }
        }
    }

    @Bean
    @Primary
    public RetryTemplate retryTemplate() {
        return createRetryTemplate(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_INITIAL_BACKOFF, DEFAULT_BACKOFF_MULTIPLIER);
    }

    public static RetryTemplate createRetryTemplate() {
        return createRetryTemplate(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_INITIAL_BACKOFF, DEFAULT_BACKOFF_MULTIPLIER);
    }

    public static RetryTemplate createRetryTemplate(int maxAttempts, Duration initialBackoff, double multiplier) {
        long maxRetries = Math.max(0, maxAttempts - 1L);
        RetryPolicy policy = RetryPolicy.builder().maxRetries(maxRetries).delay(initialBackoff).multiplier(multiplier)
                .maxDelay(DEFAULT_MAX_BACKOFF).predicate(AiConfig::isTransientAiError).build();
        RetryTemplate template = new RetryTemplate(policy);
        template.setRetryListener(new RetryListener() {
            @Override
            public void onRetryFailure(RetryPolicy retryPolicy, Retryable<?> retryable, Throwable throwable) {
                log.warn("Transient Gemini model call failed, will retry if attempts remain: {}",
                        throwable.getMessage());
            }
        });
        return template;
    }

    public static boolean isTransientAiError(Throwable t) {
        if (t == null || ExceptionUtils.isCancellation(t)) {
            return false;
        }
        boolean foundTransient = false;
        for (Throwable curr = t; curr != null; curr = curr.getCause()) {
            Boolean classification = classifyCause(curr);
            if (Boolean.FALSE.equals(classification)) {
                return false;
            }
            if (Boolean.TRUE.equals(classification)) {
                foundTransient = true;
            }
        }
        return foundTransient;
    }

    private static Boolean classifyCause(Throwable curr) {
        if (curr instanceof NonTransientAiException) {
            return Boolean.FALSE;
        }
        if (curr instanceof ApiException apiEx) {
            int code = apiEx.code();
            if (code == HTTP_TOO_MANY_REQUESTS || code == HTTP_REQUEST_TIMEOUT
                    || (code >= HTTP_INTERNAL_SERVER_ERROR && code < HTTP_SERVER_ERROR_UPPER)) {
                return Boolean.TRUE;
            }
            if (code >= HTTP_BAD_REQUEST && code < HTTP_INTERNAL_SERVER_ERROR) {
                return Boolean.FALSE;
            }
            if (hasNonRetryableClientMessage(apiEx.status()) || hasNonRetryableClientMessage(apiEx.getMessage())) {
                return Boolean.FALSE;
            }
            if (hasTransientStatusMessage(apiEx.status()) || hasTransientStatusMessage(apiEx.getMessage())) {
                return Boolean.TRUE;
            }
        }
        if (hasNonRetryableClientMessage(curr.getMessage())) {
            return Boolean.FALSE;
        }
        if (curr instanceof TransientAiException || curr instanceof ResourceAccessException
                || curr instanceof ServerException || curr instanceof GenAiIOException || curr instanceof IOException
                || curr instanceof TimeoutException || hasTransientStatusMessage(curr.getMessage())) {
            return Boolean.TRUE;
        }
        return null;
    }

    private static boolean hasNonRetryableClientMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String upper = msg.toUpperCase(Locale.ROOT);
        return NON_RETRYABLE_MARKERS.stream().anyMatch(upper::contains);
    }

    private static boolean hasTransientStatusMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String upper = msg.toUpperCase(Locale.ROOT);
        return TRANSIENT_STATUS_MARKERS.stream().anyMatch(upper::contains);
    }

    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return exception -> {
            ExceptionUtils.rethrowIfCancelled(exception, "Tool execution cancelled");
            Throwable cause = ExceptionUtils.getRootCause(exception);
            String rawMessage;
            if (cause != null && StringUtils.hasText(cause.getMessage())) {
                rawMessage = cause.getMessage();
            } else {
                rawMessage = exception.getMessage();
            }
            String message;
            if (StringUtils.hasText(rawMessage)) {
                message = rawMessage;
            } else {
                message = "Tool execution failed";
            }
            try {
                return SHARED_OBJECT_MAPPER.writeValueAsString(Map.of("error", message));
            } catch (Exception e) {
                return """
                        {"error":"Tool execution failed"}""";
            }
        };
    }

    @Bean
    @Primary
    public ToolCallingManager toolCallingManager(ToolExecutionExceptionProcessor toolExecutionExceptionProcessor) {
        return ToolCallingManager.builder().toolExecutionExceptionProcessor(toolExecutionExceptionProcessor)
                .maxCallsPerTool(DEFAULT_MAX_TOOL_CALLS).maxTotalToolCalls(DEFAULT_MAX_TOOL_CALLS).build();
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

    @Bean(destroyMethod = "close")
    public ExecutorService chatTurnExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

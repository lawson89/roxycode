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
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
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
    public static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(1500);
    public static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(15);

    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;
    private static final int HTTP_SERVER_ERROR_UPPER = 600;

    private static final ThreadLocal<Throwable> HANDLED_TERMINAL_EXCEPTION = new ThreadLocal<>();

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
                .maxDelay(DEFAULT_MAX_BACKOFF).predicate(AiConfig::shouldRetryModelCall).build();
        RetryTemplate template = new RetryTemplate(policy);
        template.setRetryListener(new RetryListener() {
            @Override
            public void onRetryFailure(RetryPolicy retryPolicy, Retryable<?> retryable, Throwable throwable) {
                log.warn("Transient Gemini model call failed, will retry if attempts remain: {}",
                        throwable.getMessage());
            }

            @Override
            public void onRetryPolicyExhaustion(RetryPolicy retryPolicy, Retryable<?> retryable,
                    RetryException exception) {
                markHandledTerminalException(exception.getCause() != null ? exception.getCause() : exception);
            }

            @Override
            public void onRetryPolicyInterruption(RetryPolicy retryPolicy, Retryable<?> retryable,
                    RetryException exception) {
                markHandledTerminalException(exception.getCause() != null ? exception.getCause() : exception);
            }

            @Override
            public void onRetryPolicyTimeout(RetryPolicy retryPolicy, Retryable<?> retryable,
                    RetryException exception) {
                markHandledTerminalException(exception.getCause() != null ? exception.getCause() : exception);
            }

            @Override
            public void onRetryableExecution(RetryPolicy retryPolicy, Retryable<?> retryable, RetryState retryState) {
                if (!retryState.isSuccessful()) {
                    Throwable lastException = retryState.getLastException();
                    if (lastException != null && !isTransientAiError(lastException)) {
                        markHandledTerminalException(lastException);
                    }
                }
            }
        });
        return template;
    }

    public static void clearHandledRetryState() {
        HANDLED_TERMINAL_EXCEPTION.remove();
    }

    public static void markHandledTerminalException(Throwable t) {
        if (t != null) {
            HANDLED_TERMINAL_EXCEPTION.set(t);
        }
    }

    static boolean shouldRetryModelCall(Throwable t) {
        if (t == null) {
            return false;
        }
        Throwable handled = HANDLED_TERMINAL_EXCEPTION.get();
        if (handled != null) {
            for (Throwable curr = t; curr != null; curr = curr.getCause()) {
                if (curr == handled) {
                    return false;
                }
            }
        }
        return isTransientAiError(t);
    }

    public static boolean isTransientAiError(Throwable t) {
        if (t == null || ExceptionUtils.isCancellation(t)) {
            return false;
        }
        for (Throwable curr = t; curr != null; curr = curr.getCause()) {
            if (curr instanceof NonTransientAiException) {
                return false;
            }
            if (curr instanceof ApiException apiEx) {
                int code = apiEx.code();
                if (code == HTTP_TOO_MANY_REQUESTS || code == HTTP_REQUEST_TIMEOUT
                        || (code >= HTTP_INTERNAL_SERVER_ERROR && code < HTTP_SERVER_ERROR_UPPER)) {
                    return true;
                }
                if (code >= HTTP_BAD_REQUEST && code < HTTP_INTERNAL_SERVER_ERROR) {
                    return false;
                }
                if (hasNonRetryableClientMessage(apiEx.status()) || hasNonRetryableClientMessage(apiEx.getMessage())) {
                    return false;
                }
                if (hasTransientStatusMessage(apiEx.status()) || hasTransientStatusMessage(apiEx.getMessage())) {
                    return true;
                }
            }
            if (hasNonRetryableClientMessage(curr.getMessage())) {
                return false;
            }
        }

        for (Throwable curr = t; curr != null; curr = curr.getCause()) {
            if (curr instanceof TransientAiException || curr instanceof ResourceAccessException
                    || curr instanceof ServerException || curr instanceof GenAiIOException
                    || curr instanceof IOException || curr instanceof TimeoutException) {
                return true;
            }
            if (hasTransientStatusMessage(curr.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonRetryableClientMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String upper = msg.toUpperCase(Locale.ROOT);
        return upper.contains("400 BAD REQUEST") || upper.contains("401 UNAUTHORIZED")
                || upper.contains("403 FORBIDDEN") || upper.contains("404 NOT FOUND")
                || upper.contains("INVALID_ARGUMENT") || upper.contains("PERMISSION_DENIED")
                || upper.contains("UNAUTHENTICATED") || upper.contains("API_KEY_INVALID");
    }

    private static boolean hasTransientStatusMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String upper = msg.toUpperCase(Locale.ROOT);
        return upper.contains("429") || upper.contains("RESOURCE_EXHAUSTED") || upper.contains("TOO MANY REQUESTS")
                || upper.contains("RATE LIMIT") || upper.contains("500 INTERNAL") || upper.contains("502 BAD GATEWAY")
                || upper.contains("503 SERVICE UNAVAILABLE") || upper.contains("504 GATEWAY TIMEOUT")
                || upper.contains("UNAVAILABLE") || upper.contains("DEADLINE_EXCEEDED")
                || upper.contains("SOCKET TIMEOUT") || upper.contains("READ TIMED OUT")
                || upper.contains("CONNECTION RESET");
    }

    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return exception -> {
            ExceptionUtils.rethrowIfCancelled(exception, "Tool execution cancelled");
            Throwable cause = ExceptionUtils.getRootCause(exception);
            return cause != null && cause.getMessage() != null ? cause.getMessage() : exception.getMessage();
        };
    }

    @Bean
    @Primary
    public ToolCallingManager toolCallingManager(ToolExecutionExceptionProcessor toolExecutionExceptionProcessor) {
        return ToolCallingManager.builder().toolExecutionExceptionProcessor(toolExecutionExceptionProcessor).build();
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

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
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
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;

/** Configuration for AI-related beans. */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    public static final int DEFAULT_HTTP_TIMEOUT_MS = 120_000;
    public static final String UNCONFIGURED_API_KEY_PLACEHOLDER = "unconfigured-api-key";
    public static final int DEFAULT_MAX_TOOL_CALLS = 100;
    public static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(2000);
    public static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(30);
    public static final Duration DEFAULT_BACKOFF_JITTER = Duration.ofMillis(500);
    public static final Duration DEFAULT_RATE_LIMIT_COOLDOWN = Duration.ofSeconds(60);

    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_CLIENT_CLOSED_REQUEST = 499;
    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;
    private static final int HTTP_SERVER_ERROR_UPPER = 600;

    private static final List<String> NON_RETRYABLE_MARKERS = List.of("400 BAD REQUEST", "401 UNAUTHORIZED",
            "403 FORBIDDEN", "404 NOT FOUND", "INVALID_ARGUMENT", "PERMISSION_DENIED", "UNAUTHENTICATED",
            "API_KEY_INVALID");

    private static final List<String> RATE_LIMIT_MARKERS = List.of("429", "RESOURCE_EXHAUSTED", "TOO MANY REQUESTS",
            "RATE LIMIT", "RATE_LIMIT", "QUOTA EXCEEDED", "QUOTA_EXCEEDED");

    private static final List<String> TRANSIENT_STATUS_MARKERS = List.of("429", "499", "RESOURCE_EXHAUSTED",
            "TOO MANY REQUESTS", "RATE LIMIT", "RATE_LIMIT", "QUOTA EXCEEDED", "QUOTA_EXCEEDED", "500 INTERNAL",
            "502 BAD GATEWAY", "503 SERVICE UNAVAILABLE", "504 GATEWAY TIMEOUT", "INTERNAL", "UNAVAILABLE",
            "OVERLOADED", "TRY AGAIN LATER", "DEADLINE_EXCEEDED", "SOCKET TIMEOUT", "READ TIMED OUT",
            "CONNECTION RESET", "CONNECTION CLOSED", "REMOTELY CLOSED", "BROKEN PIPE", "UNEXPECTED END OF STREAM",
            "EOF", "GOAWAY");

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

    /**
     * Normalized connection configuration for Google GenAI (either Gemini API Key mode or Google Cloud Vertex AI mode).
     *
     * @param vertexAi
     *            {@code true} to connect via Google Cloud Vertex AI, {@code false} for Gemini Developer API
     * @param apiKey
     *            the Gemini API key (used when {@code vertexAi} is {@code false})
     * @param projectId
     *            the GCP Project ID (used when {@code vertexAi} is {@code true})
     * @param location
     *            the Vertex AI location/region (used when {@code vertexAi} is {@code true})
     * @param credentialsPath
     *            optional file path to a Service Account JSON key file for Vertex AI (blank uses ADC)
     */
    public record GenAiConnectionConfig(boolean vertexAi, String apiKey, String projectId, String location,
            String credentialsPath) {

        public GenAiConnectionConfig {
            apiKey = trimToEmpty(apiKey);
            projectId = trimToEmpty(projectId);
            location = trimToEmpty(location);
            credentialsPath = trimToEmpty(credentialsPath);
        }

        private static String trimToEmpty(String value) {
            if (value == null) {
                return "";
            }
            return value.trim();
        }

        /**
         * Creates a connection configuration for Gemini Developer API Key mode.
         *
         * @param apiKey
         *            the Gemini API key
         * @return the connection configuration
         */
        public static GenAiConnectionConfig forApiKey(String apiKey) {
            return new GenAiConnectionConfig(false, apiKey, "", "", "");
        }

        /**
         * Creates a connection configuration for Google Cloud Vertex AI mode.
         *
         * @param projectId
         *            the GCP project ID
         * @param location
         *            the Vertex AI location/region
         * @param credentialsPath
         *            optional path to a Service Account JSON key file (blank for ADC)
         * @return the connection configuration
         */
        public static GenAiConnectionConfig forVertexAi(String projectId, String location, String credentialsPath) {
            return new GenAiConnectionConfig(true, "", projectId, location, credentialsPath);
        }

        /**
         * Extracts the connection configuration from {@link AppSettings}.
         *
         * @param settings
         *            the application settings
         * @return the normalized connection configuration
         */
        public static GenAiConnectionConfig fromSettings(AppSettings settings) {
            if (settings == null) {
                return forApiKey("");
            }
            if (settings.isVertexAiEnabled()) {
                return new GenAiConnectionConfig(true, settings.getGeminiApiKey(), settings.getVertexProjectId(),
                        settings.getVertexLocation(), settings.getVertexCredentialsPath());
            }
            return new GenAiConnectionConfig(false, settings.getGeminiApiKey(), settings.getVertexProjectId(),
                    settings.getVertexLocation(), settings.getVertexCredentialsPath());
        }

        /**
         * Checks whether required credentials for the selected mode are present.
         *
         * @return {@code true} if configured
         */
        public boolean isConfigured() {
            if (vertexAi) {
                return StringUtils.hasText(projectId) && StringUtils.hasText(location);
            }
            return StringUtils.hasText(apiKey);
        }
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
                log.info(
                        "Vertex AI enabled without complete project-id/location on startup; using placeholder client until configured in Settings.");
                builder.apiKey(UNCONFIGURED_API_KEY_PLACEHOLDER);
            } else {
                configureVertexAi(builder, properties);
            }
        } else if (hasApiKey) {
            builder.apiKey(properties.getApiKey());
        } else if (hasVertexConfig) {
            configureVertexAi(builder, properties);
        } else {
            builder.apiKey(UNCONFIGURED_API_KEY_PLACEHOLDER);
        }
        return builder.build();
    }

    /**
     * Creates a Google GenAI {@link Client} for the given API key and HTTP timeout.
     *
     * @param apiKey
     *            the Gemini API key
     * @param httpTimeoutMs
     *            the HTTP timeout in milliseconds
     * @return the configured {@link Client}
     */
    public static Client createGenAiClient(String apiKey, int httpTimeoutMs) {
        return createGenAiClient(GenAiConnectionConfig.forApiKey(apiKey), httpTimeoutMs);
    }

    /**
     * Creates a Google GenAI {@link Client} for the given {@link GenAiConnectionConfig} and HTTP timeout.
     *
     * @param config
     *            the connection configuration (Gemini API Key or Vertex AI)
     * @param httpTimeoutMs
     *            the HTTP timeout in milliseconds
     * @return the configured {@link Client}
     */
    public static Client createGenAiClient(GenAiConnectionConfig config, int httpTimeoutMs) {
        if (config == null) {
            throw new IllegalArgumentException("Connection configuration must not be null.");
        }
        Client.Builder builder = Client.builder();
        if (httpTimeoutMs > 0) {
            builder.httpOptions(HttpOptions.builder().timeout(httpTimeoutMs).build());
        }
        if (config.vertexAi()) {
            if (!StringUtils.hasText(config.projectId())) {
                throw new IllegalArgumentException("Please enter a GCP Project ID for Vertex AI.");
            }
            if (!StringUtils.hasText(config.location())) {
                throw new IllegalArgumentException("Please enter a Vertex Location (e.g. global or us-central1).");
            }
            builder.project(config.projectId()).location(config.location()).vertexAI(true);
            if (StringUtils.hasText(config.credentialsPath())) {
                builder.credentials(loadCredentialsFromFile(Path.of(config.credentialsPath())));
            }
        } else {
            String effectiveApiKey = config.apiKey();
            if (!StringUtils.hasText(effectiveApiKey)) {
                effectiveApiKey = UNCONFIGURED_API_KEY_PLACEHOLDER;
            }
            builder.apiKey(effectiveApiKey);
        }
        return builder.build();
    }

    static GoogleCredentials loadCredentialsFromFile(Path credentialsFile) {
        if (credentialsFile == null || !Files.isRegularFile(credentialsFile)) {
            throw new IllegalArgumentException("Service Account JSON file not found: " + credentialsFile);
        }
        try (InputStream is = Files.newInputStream(credentialsFile)) {
            return GoogleCredentials.fromStream(is);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read Service Account JSON credentials from '"
                    + credentialsFile + "': " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public static GoogleGenAiChatModel createChatModel(Client client, String defaultModel,
            ToolCallingManager toolCallingManager) {
        String modelName = AppSettings.DEFAULT_GEMINI_MODEL;
        if (StringUtils.hasText(defaultModel)) {
            modelName = defaultModel.trim();
        }
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder().model(modelName).build();
        GoogleGenAiChatModel.Builder builder = GoogleGenAiChatModel.builder().genAiClient(client).options(options)
                .retryTemplate(createNoOpRetryTemplate());
        if (toolCallingManager != null) {
            builder.toolCallingManager(toolCallingManager);
        }
        return builder.build();
    }

    private static void configureVertexAi(Client.Builder builder, GoogleGenAiConnectionProperties props)
            throws IOException {
        builder.project(props.getProjectId().trim()).location(props.getLocation().trim()).vertexAI(true);
        if (props.getCredentialsUri() != null) {
            try (InputStream is = props.getCredentialsUri().getInputStream()) {
                builder.credentials(GoogleCredentials.fromStream(is));
            }
        }
    }

    @Bean
    @Primary
    public RetryTemplate retryTemplate() {
        return createNoOpRetryTemplate();
    }

    private static RetryTemplate createNoOpRetryTemplate() {
        RetryPolicy policy = RetryPolicy.builder().maxRetries(0).build();
        return new RetryTemplate(policy);
    }

    public static RetryTemplate createRetryTemplate() {
        return createRetryTemplate(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_INITIAL_BACKOFF, DEFAULT_BACKOFF_MULTIPLIER,
                DEFAULT_MAX_BACKOFF, DEFAULT_BACKOFF_JITTER);
    }

    public static RetryTemplate createRetryTemplate(int maxAttempts, Duration initialBackoff, double multiplier) {
        return createRetryTemplate(maxAttempts, initialBackoff, multiplier, DEFAULT_MAX_BACKOFF, Duration.ZERO);
    }

    public static RetryTemplate createRetryTemplate(int maxAttempts, Duration initialBackoff, double multiplier,
            Duration maxBackoff, Duration jitter) {
        long maxRetries = Math.max(0, maxAttempts - 1L);
        RetryPolicy.Builder policyBuilder = RetryPolicy.builder().maxRetries(maxRetries).delay(initialBackoff)
                .multiplier(multiplier).maxDelay(maxBackoff).predicate(AiConfig::isTransientAiError);
        if (jitter != null && !jitter.isZero() && !jitter.isNegative()) {
            policyBuilder.jitter(jitter);
        }
        RetryPolicy policy = policyBuilder.build();
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
        boolean foundTypedTransient = false;
        boolean foundMessageNonRetryable = false;
        boolean foundMessageTransient = false;
        for (Throwable curr = t; curr != null; curr = curr.getCause()) {
            Boolean typedClassification = classifyTypedCause(curr);
            if (Boolean.FALSE.equals(typedClassification)) {
                return false;
            }
            if (Boolean.TRUE.equals(typedClassification)) {
                foundTypedTransient = true;
            }
            if (hasNonRetryableClientMessage(curr.getMessage())) {
                foundMessageNonRetryable = true;
            }
            if (hasTransientStatusMessage(curr.getMessage())) {
                foundMessageTransient = true;
            }
        }
        if (foundTypedTransient) {
            return true;
        }
        if (foundMessageNonRetryable) {
            return false;
        }
        return foundMessageTransient;
    }

    private static Boolean classifyTypedCause(Throwable curr) {
        if (curr instanceof NonTransientAiException) {
            return Boolean.FALSE;
        }
        if (curr instanceof ApiException apiEx) {
            return classifyApiException(apiEx);
        }
        if (curr instanceof TransientAiException || curr instanceof ResourceAccessException
                || curr instanceof ServerException || curr instanceof GenAiIOException || curr instanceof IOException
                || curr instanceof TimeoutException) {
            return Boolean.TRUE;
        }
        return null;
    }

    private static Boolean classifyApiException(ApiException apiEx) {
        int code = apiEx.code();
        if (code == HTTP_TOO_MANY_REQUESTS || code == HTTP_REQUEST_TIMEOUT || code == HTTP_CLIENT_CLOSED_REQUEST
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
        if (apiEx instanceof ServerException) {
            return Boolean.TRUE;
        }
        return null;
    }

    private static boolean hasNonRetryableClientMessage(String msg) {
        return containsAnyMarker(msg, NON_RETRYABLE_MARKERS);
    }

    /**
     * Determines whether a throwable represents a Gemini API rate-limit or quota exhaustion error (HTTP 429 /
     * {@code RESOURCE_EXHAUSTED}).
     *
     * @param t
     *            the throwable to inspect
     * @return {@code true} if the error is a retryable rate-limit or quota exhaustion error
     */
    public static boolean isRateLimitError(Throwable t) {
        if (!isTransientAiError(t)) {
            return false;
        }
        for (Throwable curr = t; curr != null; curr = curr.getCause()) {
            if (curr instanceof ApiException apiEx) {
                if (apiEx.code() == HTTP_TOO_MANY_REQUESTS || hasRateLimitMessage(apiEx.status())
                        || hasRateLimitMessage(apiEx.getMessage())) {
                    return true;
                }
            }
            if (hasRateLimitMessage(curr.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRateLimitMessage(String msg) {
        return containsAnyMarker(msg, RATE_LIMIT_MARKERS);
    }

    private static boolean hasTransientStatusMessage(String msg) {
        return containsAnyMarker(msg, TRANSIENT_STATUS_MARKERS);
    }

    private static boolean containsAnyMarker(String msg, List<String> markers) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String upper = msg.toUpperCase(Locale.ROOT);
        return markers.stream().anyMatch(upper::contains);
    }

    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return exception -> {
            ExceptionUtils.rethrowIfCancelled(exception, "Tool execution cancelled");
            String message;
            if (exception != null && (StringUtils.hasText(exception.getMessage())
                    || StringUtils.hasText(ExceptionUtils.getRootCause(exception).getMessage()))) {
                message = ExceptionUtils.getRootCauseMessage(exception);
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
    public JexlServiceRegistry jexlServiceRegistry(ApplicationContext context, SystemToolService systemToolService,
            org.roxycode.app.service.SettingsService settingsService) {
        JexlServiceRegistry registry = new JexlServiceRegistry(systemToolService, settingsService);
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

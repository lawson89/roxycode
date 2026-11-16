package org.roxycode.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.jexl3.JexlArithmetic;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlUberspect;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.roxycode.app.events.AgentActivityEvent;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.ui.ChatHtmlTemplates;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Orchestrates the execution of JEXL scripts against registered workspace services. This tool provides the primary
 * interface for the AI agent to interact with the project codebase.
 */
@Component
public class JexlTool {

    private static final Logger log = LoggerFactory.getLogger(JexlTool.class);
    private static final String AGENT_NAME = "Roxy";
    private static final String RESULT_KEY = "result";
    private static final long NANOS_PER_MILLI = 1_000_000L;
    public static final String MAX_TURNS_EXCEEDED_PREFIX = "Autonomous execution stopped: Maximum tool turns (";

    private final JexlFeatures jexlFeatures;
    private final JexlUberspect baseUberspect;
    private final JexlServiceRegistry registry;
    private final SettingsService settingsService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper mapper;
    private final JsPolyfillUberspect.JsJsonGlobal jsonGlobal;

    public JexlTool(JexlServiceRegistry registry, @Nullable ApplicationEventPublisher eventPublisher,
            @Nullable ObjectMapper objectMapper, @Nullable SettingsService settingsService) {
        this.jexlFeatures = new JexlFeatures().newInstance(false).fatArrow(true);
        this.baseUberspect = new JexlBuilder().features(this.jexlFeatures).permissions(AgentJexlPermissions.INSTANCE)
                .arithmetic(new MutableMapJexlArithmetic(true)).strict(true).silent(false).safe(false).create()
                .getUberspect();
        this.registry = registry;
        this.eventPublisher = eventPublisher;
        this.mapper = Objects.requireNonNullElse(objectMapper, AiConfig.SHARED_OBJECT_MAPPER);
        this.jsonGlobal = new JsPolyfillUberspect.JsJsonGlobal(this.mapper);
        this.settingsService = settingsService;
    }

    /**
     * Executes a JEXL script in the current project context.
     *
     * @param script
     *            the JEXL script to execute
     * @return the serialized result of the script execution
     */
    public String executeJexl(String script) {
        return executeJexl(script, null);
    }

    /**
     * Executes a JEXL script within a specific tool context (turn tracking and conversation state).
     *
     * @param script
     *            the JEXL script to execute
     * @param toolContext
     *            the Spring AI tool context providing conversation and turn details
     * @return the serialized result of the script execution
     */
    @Tool(description = "Executes a Jexl script and returns the result as a string. Useful for workspace file, search, git, and build operations.")
    public String executeJexl(@ToolParam(description = "The Jexl script to execute.") String script,
            ToolContext toolContext) {
        ExceptionUtils.checkInterrupted("Execution interrupted before start");
        TurnToolContext ctx = TurnToolContext.fromToolContext(toolContext);
        String conversationId = ctx.conversationId();
        WorkflowPhase phase = ctx.phase();

        int maxTurns = resolveMaxTurns();
        if (ctx.turnCounter().get() >= maxTurns) {
            String stopMsg = MAX_TURNS_EXCEEDED_PREFIX + maxTurns
                    + ") reached. Stop calling tools and summarize your progress for the user.";
            log.warn("Max tool turns ({}) reached in phase {} for conversation {}", maxTurns, phase, conversationId);
            return toJsonError(stopMsg);
        }

        int currentTurn = ctx.turnCounter().incrementAndGet();
        String scriptSummary = ChatHtmlTemplates.summarizeToolScript(script);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(
                    new AgentTurnEvent(conversationId, AGENT_NAME, currentTurn, phase.name(), scriptSummary));
        }

        log.info("Executing JEXL script in phase {} (turn {}/{}): {}", phase, currentTurn, maxTurns, script);

        Map<String, Object> bindings = new HashMap<>(registry.getServices(phase, conversationId));
        bindings.put("JSON", jsonGlobal);
        bindings.put("Object", JsPolyfillUberspect.OBJECT_GLOBAL);
        JexlContext context = new MapContext(bindings);
        JexlEngine jexl = createEngine(context);

        long startNanos = System.nanoTime();
        boolean cancelled = false;
        try {
            JexlScript jexlScript;
            try {
                jexlScript = jexl.createScript(script);
            } catch (Exception e) {
                JexlErrorAnalyzer.ErrorAnalysis analysis = JexlErrorAnalyzer.analyze(e, script, true);
                log.warn("JEXL Syntax Error [{}] intercepted: {}", analysis.errorCategory(), analysis.error());
                publishToolEvent(ctx, currentTurn, elapsedMillis(startNanos), script, null, false, analysis);
                return toJsonError(analysis);
            }

            Object result = jexlScript.execute(context);
            ExceptionUtils.checkInterrupted("JEXL execution cancelled");
            publishToolEvent(ctx, currentTurn, elapsedMillis(startNanos), script, result, true, null);
            return serializeResult(result);
        } catch (Exception e) {
            if (ExceptionUtils.isCancellation(e)) {
                cancelled = true;
                try {
                    publishToolEvent(ctx, currentTurn, elapsedMillis(startNanos), script, null, false,
                            new JexlErrorAnalyzer.ErrorAnalysis(JexlErrorAnalyzer.ErrorCategory.RUNTIME_ERROR.name(),
                                    null, null, null, "Execution cancelled", null, false, e.getClass().getName(), null,
                                    List.of(), List.of()));
                } catch (Exception ignored) {
                }
                throw ExceptionUtils.asCancellationException(e, "JEXL execution cancelled");
            }
            JexlErrorAnalyzer.ErrorAnalysis analysis = JexlErrorAnalyzer.analyze(e, script, false);
            log.warn("Error executing JEXL script [{}]: {}", analysis.errorCategory(), analysis.error());
            publishToolEvent(ctx, currentTurn, elapsedMillis(startNanos), script, null, false, analysis);
            return toJsonError(analysis);
        } finally {
            if (!cancelled && !Thread.currentThread().isInterrupted() && eventPublisher != null) {
                eventPublisher.publishEvent(new AgentActivityEvent(conversationId, "Analyzing tool output..."));
            }
        }
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / NANOS_PER_MILLI);
    }

    private JexlEngine createEngine(JexlContext context) {
        return new JexlBuilder().features(jexlFeatures).permissions(AgentJexlPermissions.INSTANCE)
                .arithmetic(new MutableMapJexlArithmetic(true))
                .uberspect(new JsPolyfillUberspect(baseUberspect, context)).strict(true).silent(false).safe(false)
                .create();
    }

    private int resolveMaxTurns() {
        if (settingsService != null && settingsService.getSettings() != null) {
            return Math.max(1, settingsService.getSettings().getMaxAgentToolTurns());
        }
        return AppSettings.DEFAULT_MAX_AGENT_TOOL_TURNS;
    }

    private String serializeResult(@Nullable Object result) throws Exception {
        if (result == null) {
            return mapper.writeValueAsString(Map.of(RESULT_KEY, "null"));
        }
        if (result instanceof Map) {
            return mapper.writeValueAsString(result);
        }
        if (isScalarResult(result) || AgentJexlPermissions.isAgentServiceClass(result.getClass())) {
            return serializeStringResult(result.toString());
        }
        try {
            return mapper.writeValueAsString(Map.of(RESULT_KEY, result));
        } catch (Exception ex) {
            return serializeStringResult(result.toString());
        }
    }

    private static boolean isScalarResult(Object result) {
        return result instanceof CharSequence || result instanceof Number || result instanceof Boolean
                || result instanceof Character || result instanceof Enum<?>;
    }

    private String serializeStringResult(String rawOutput) throws Exception {
        return mapper.writeValueAsString(Map.of(RESULT_KEY, StringUtils.defaultIfBlank(rawOutput, "<empty result>")));
    }

    private void publishToolEvent(TurnToolContext ctx, int turnNumber, long durationMs, String script,
            @Nullable Object result, boolean success, JexlErrorAnalyzer.@Nullable ErrorAnalysis analysis) {
        if (eventPublisher == null) {
            return;
        }
        String convId = ctx.conversationId();
        String phaseName = ctx.phase().name();
        String modelName = ctx.modelName();
        if (analysis == null) {
            eventPublisher.publishEvent(new ToolExecutionEvent(convId, phaseName, script, result, success, null, null,
                    null, null, null, null, modelName, turnNumber, durationMs, false, null, null, null, null));
            return;
        }
        eventPublisher.publishEvent(new ToolExecutionEvent(convId, phaseName, script, result, success,
                analysis.errorCategory(), analysis.line(), analysis.column(), analysis.offendingLine(),
                analysis.error(), analysis.hint(), modelName, turnNumber, durationMs, analysis.syntaxStage(),
                analysis.exceptionType(), analysis.rootCauseType(), analysis.causeChain(), analysis.appStackFrames()));
    }

    private String toJsonError(JexlErrorAnalyzer.ErrorAnalysis analysis) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("error", Objects.requireNonNullElse(analysis.error(), "Unknown error"));
            if (StringUtils.isNotBlank(analysis.hint())) {
                payload.put("hint", analysis.hint());
            }
            if (StringUtils.isNotBlank(analysis.offendingLine())) {
                payload.put("offendingLine", analysis.offendingLine());
            }
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return """
                    {"error":"Internal Error during error serialization"}""";
        }
    }

    private String toJsonError(String message) {
        try {
            return mapper.writeValueAsString(Map.of("error", Objects.requireNonNullElse(message, "Unknown error")));
        } catch (Exception e) {
            return """
                    {"error":"Internal Error during error serialization"}""";
        }
    }

    /**
     * Custom {@link JexlArithmetic} that ensures empty map literals {@code {}} construct a mutable
     * {@link LinkedHashMap} rather than an immutable empty map, enabling {@code map[key] = value} assignments.
     */
    private static final class MutableMapJexlArithmetic extends JexlArithmetic {

        private MutableMapJexlArithmetic(boolean astrict) {
            super(astrict);
        }

        @Override
        public MapBuilder mapBuilder(int size, boolean extended) {
            return new MutableMapBuilder(size);
        }

        @Override
        public MapBuilder mapBuilder(int size) {
            return new MutableMapBuilder(size);
        }

        @Override
        public SetBuilder setBuilder(int size, boolean extended) {
            if (size == 0) {
                return new EmptyBracesAsMutableMapBuilder();
            }
            return super.setBuilder(size, extended);
        }

        @Override
        public SetBuilder setBuilder(int size) {
            if (size == 0) {
                return new EmptyBracesAsMutableMapBuilder();
            }
            return super.setBuilder(size);
        }
    }

    private static final class EmptyBracesAsMutableMapBuilder implements JexlArithmetic.SetBuilder {

        private final Map<Object, Object> map = new LinkedHashMap<>();

        @Override
        public void add(Object value) {
            // Only used for empty '{}' literals where no elements are added
        }

        @Override
        public Object create() {
            return map;
        }
    }

    private static final class MutableMapBuilder implements JexlArithmetic.MapBuilder {

        private final Map<Object, Object> map;

        private MutableMapBuilder(int size) {
            this.map = new LinkedHashMap<>(Math.max(size, 0));
        }

        @Override
        public void put(Object key, Object value) {
            map.put(key, value);
        }

        @Override
        public Object create() {
            return map;
        }
    }
}

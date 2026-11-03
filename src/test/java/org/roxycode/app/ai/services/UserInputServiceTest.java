package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.TurnToolContext;
import org.roxycode.app.events.UserInputRequestEvent;
import org.roxycode.app.events.UserInputRequestEvent.PromptType;

class UserInputServiceTest {

    @Test
    void testConfirmAskTextSelectOptionAndSelectMultiple() {
        AtomicReference<UserInputRequestEvent> lastEvent = new AtomicReference<>();
        UserInputService rootService = new UserInputService(event -> {
            if (event instanceof UserInputRequestEvent uire) {
                lastEvent.set(uire);
                if (uire.promptType() == PromptType.CONFIRM) {
                    uire.responseFuture().complete(uire.details() == null);
                } else if (uire.promptType() == PromptType.TEXT) {
                    uire.responseFuture().complete(uire.defaultValue() + "-submitted");
                } else if (uire.promptType() == PromptType.SINGLE_SELECT) {
                    uire.responseFuture().complete(uire.options().get(1));
                } else if (uire.promptType() == PromptType.MULTI_SELECT) {
                    uire.responseFuture().complete(List.of(uire.options().get(0), uire.options().get(2)));
                }
            }
        });

        assertEquals(TurnToolContext.DEFAULT_CONVERSATION_ID, rootService.getConversationId());
        UserInputService scoped = rootService.forConversation("task:default");
        assertEquals("task:default", scoped.getConversationId());

        assertTrue(scoped.confirm("  Proceed with migration?  "));
        assertEquals("task:default", lastEvent.get().conversationId());
        assertEquals(PromptType.CONFIRM, lastEvent.get().promptType());
        assertEquals("Proceed with migration?", lastEvent.get().question());
        assertNull(lastEvent.get().details());

        assertFalse(scoped.confirm("Delete files?", "  Removes 3 files  "));
        assertEquals("Removes 3 files", lastEvent.get().details());

        assertEquals("-submitted", scoped.askText("Enter name:"));
        assertEquals("main-submitted", scoped.askText("Enter branch:", "main"));
        assertEquals("main", lastEvent.get().defaultValue());

        assertEquals("Gradle", scoped.selectOption("Choose build tool:", List.of(" Maven ", " Gradle ")));
        assertEquals(List.of("Maven", "Gradle"), lastEvent.get().options());
        assertEquals("Gradle", scoped.selectOption("Choose build tool:", "Maven", "Gradle"));

        assertEquals(List.of("core", "ui"), scoped.selectMultiple("Select modules:", List.of("core", "api", "ui")));
        assertEquals(List.of("core", "ui"), scoped.selectMultiple("Select modules:", "core", "api", "ui"));
    }

    @Test
    void testConcurrentConversationsIsolationReplacementAndCancellation() throws Exception {
        Map<String, UserInputRequestEvent> eventsByConv = new ConcurrentHashMap<>();
        CountDownLatch bothPublished = new CountDownLatch(2);

        UserInputService rootService = new UserInputService(event -> {
            if (event instanceof UserInputRequestEvent uire) {
                eventsByConv.put(uire.conversationId(), uire);
                bothPublished.countDown();
            }
        });

        UserInputService qaService = rootService.forConversation("qa:default");
        UserInputService taskService = rootService.forConversation("task:default");

        CompletableFuture<String> qaCall = CompletableFuture
                .supplyAsync(() -> qaService.askText("QA question", "qa-default"));
        CompletableFuture<Boolean> taskCall = CompletableFuture.supplyAsync(() -> taskService.confirm("Task question"));

        assertTrue(bothPublished.await(5, TimeUnit.SECONDS));
        assertNotNull(eventsByConv.get("qa:default"));
        assertNotNull(eventsByConv.get("task:default"));

        eventsByConv.get("qa:default").responseFuture().complete("qa-answer");
        assertEquals("qa-answer", qaCall.get(5, TimeUnit.SECONDS));
        assertFalse(taskCall.isDone());

        assertTrue(rootService.cancelPendingRequest("task:default"));
        assertFalse(rootService.cancelPendingRequest("task:default"));
        assertFalse(rootService.cancelPendingRequest("   "));

        ExecutionExceptionWrapper taskFailure = captureFailure(taskCall);
        assertInstanceOf(CancellationException.class, taskFailure.cause());

        // Starting a new request on the same conversation cancels the previous in-flight future
        CountDownLatch firstPublished = new CountDownLatch(1);
        CountDownLatch secondPublished = new CountDownLatch(1);
        AtomicReference<UserInputRequestEvent> firstEventRef = new AtomicReference<>();
        AtomicReference<UserInputRequestEvent> secondEventRef = new AtomicReference<>();

        UserInputService replaceRoot = new UserInputService(event -> {
            if (event instanceof UserInputRequestEvent uire) {
                if (firstEventRef.compareAndSet(null, uire)) {
                    firstPublished.countDown();
                } else {
                    secondEventRef.set(uire);
                    secondPublished.countDown();
                }
            }
        });
        UserInputService replaceScoped = replaceRoot.forConversation("conv-1");
        CompletableFuture<String> firstCall = CompletableFuture
                .supplyAsync(() -> replaceScoped.askText("First question"));
        assertTrue(firstPublished.await(5, TimeUnit.SECONDS));

        CompletableFuture<String> secondCall = CompletableFuture
                .supplyAsync(() -> replaceScoped.askText("Second question"));
        assertTrue(secondPublished.await(5, TimeUnit.SECONDS));

        assertTrue(firstEventRef.get().responseFuture().isCancelled());
        assertInstanceOf(CancellationException.class, captureFailure(firstCall).cause());

        replaceRoot.cancelAllPendingRequests();
        assertInstanceOf(CancellationException.class, captureFailure(secondCall).cause());
    }

    @Test
    void testValidationErrorsAndNullPublisherHandling() {
        UserInputService service = new UserInputService(event -> {
        });

        assertThrows(IllegalArgumentException.class, () -> service.confirm("   "));
        assertThrows(IllegalArgumentException.class, () -> service.askText(null));
        assertThrows(IllegalArgumentException.class, () -> service.selectOption("Pick", List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.selectOption("Pick", (String[]) null));
        assertThrows(IllegalArgumentException.class, () -> service.selectOption("Pick", Arrays.asList("valid", "   ")));
        assertThrows(IllegalArgumentException.class, () -> service.selectMultiple("Pick", List.of()));

        UserInputService noPublisherService = new UserInputService(null);
        assertThrows(IllegalStateException.class, () -> noPublisherService.confirm("Proceed?"));
    }

    private record ExecutionExceptionWrapper(Throwable cause) {
    }

    private static ExecutionExceptionWrapper captureFailure(CompletableFuture<?> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("Expected future to fail");
            return null;
        } catch (Exception e) {
            if (e instanceof CancellationException) {
                return new ExecutionExceptionWrapper(e);
            }
            if (e.getCause() != null) {
                return new ExecutionExceptionWrapper(e.getCause());
            }
            return new ExecutionExceptionWrapper(e);
        }
    }
}

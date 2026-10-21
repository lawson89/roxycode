package org.roxycode.app.ui;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** History panel for Feature/Task mode. */
@Component
@Lazy
public final class FeatureHistoryPanel extends HistoryPanel {
    private static final long serialVersionUID = 1L;

    public FeatureHistoryPanel(ChatMemory chatMemory) {
        super(chatMemory, ChatModel.TASK_SESSION_PREFIX);
    }
}

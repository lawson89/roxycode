package org.roxycode.app.ui;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** History panel for Explore (Q&A) mode. */
@Component
@Lazy
public final class ExploreHistoryPanel extends HistoryPanel {
    private static final long serialVersionUID = 1L;

    public ExploreHistoryPanel(ChatMemory chatMemory) {
        super(chatMemory, ChatModel.QA_SESSION_PREFIX);
    }
}

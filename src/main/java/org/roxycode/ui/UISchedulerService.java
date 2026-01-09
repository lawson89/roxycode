package org.roxycode.ui;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.ui.views.ChatView;

import javax.swing.*;

@Singleton
@io.micronaut.context.annotation.Requires(notEnv = "test")
public class UISchedulerService {

    @Inject
    private ChatView chatView;

    @Inject
    private MainFrame mainFrame;

    @Scheduled(fixedDelay = "2s", initialDelay = "1s")
    void updateUI() {
        SwingUtilities.invokeLater(() -> {
            if (chatView != null) {
                chatView.updateCacheStatus();
                chatView.updateChatStats();
            }
            if (mainFrame != null) {
                mainFrame.updateRoxyMode();
            }
        });
    }
}

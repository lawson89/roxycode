package org.roxycode.ui;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.ui.views.ChatView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;

@Singleton
@io.micronaut.context.annotation.Requires(notEnv = "test")
public class UISchedulerService {

    private static final Logger LOG = LoggerFactory.getLogger(UISchedulerService.class);

    @Inject
    private ChatView chatView;

    @Inject
    private MainFrame mainFrame;

    @Scheduled(fixedDelay = "2s")
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

package org.roxycode.ui;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.ui.views.LogsView;
import org.roxycode.ui.views.CurrentPlanView;
import org.roxycode.ui.views.ChatView;

import javax.swing.*;

@Singleton
@io.micronaut.context.annotation.Requires(notEnv = "test")
public class UISchedulerService {

    @Inject
    private CurrentPlanView currentPlanView;
    @Inject
    private ChatView chatView;
    @Inject
    private LogsView logsView;

    @Inject
    private MainFrame mainFrame;

    @Scheduled(fixedDelay = "1s", initialDelay = "1s")
    void updateUI() {
        SwingUtilities.invokeLater(() -> {
            if (chatView != null) {
                chatView.updateCacheStatus();
                chatView.updateChatStats();
                chatView.updatePlanContext();
            }
            if (mainFrame != null) {
                mainFrame.updateRoxyMode();
            }
            if (logsView != null) {
                logsView.autoRefresh();
            }
            if (currentPlanView != null) {
                currentPlanView.refresh();
            }
        });
    }
}

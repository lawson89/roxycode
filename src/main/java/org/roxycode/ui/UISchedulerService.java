package org.roxycode.ui;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.ui.views.ChatView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class UISchedulerService {

    private static final Logger LOG = LoggerFactory.getLogger(UISchedulerService.class);

    @Inject
    private ChatView chatView;

    @Scheduled(fixedDelay = "5s")
    void updateUI() {
//        LOG.debug("Scheduled UI update");
        if (chatView != null) {
            chatView.updateCacheStatus();
        }
    }
}

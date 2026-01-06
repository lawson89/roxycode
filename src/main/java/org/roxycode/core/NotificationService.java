package org.roxycode.core;

import jakarta.inject.Singleton;
import java.util.function.Consumer;

@Singleton
public class NotificationService {

    public record NotificationRequest(String message, NotificationType type) {}

    private Consumer<NotificationRequest> listener;

    public void setListener(Consumer<NotificationRequest> listener) {
        this.listener = listener;
    }

    public void showNotification(String message) {
        showNotification(message, NotificationType.INFO);
    }

    public void showNotification(String message, NotificationType type) {
        if (listener != null) {
            listener.accept(new NotificationRequest(message, type));
        }
    }
}

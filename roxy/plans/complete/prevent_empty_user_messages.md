# Goal
Prevent the user from sending empty messages in the chat UI and provide a visual warning.

# Proposed Changes
- Modify ChatView.onSend() to check if the message is empty or only whitespace.
- Inject NotificationService into ChatView.
- Show a WARNING notification if the message is empty.

# Implementation Steps
- [ ] Update ChatView constructor to include NotificationService.
- [ ] Implement message validation in ChatView.onSend().
- [ ] Use notificationService.showNotification for feedback.

# Implementation Progress
- [x] Update ChatView to use NotificationService [x]
- [x] Implement message validation in onSend [x]
- [x] Provide visual feedback via NotificationService [x]

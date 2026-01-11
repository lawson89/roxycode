# Goal
Add a new setting to specify the maximum requests per minute (rate limit) for the Gemini API.

# Proposed Changes
- Update SettingsService to include a rate limit setting with a default value of 10.
- Update SettingsView.xml to include a field for the rate limit setting.
- Update SettingsView.java to handle loading and saving the new rate limit setting.

# Implementation Steps
- [ ] 1. Add KEY_RATE_LIMIT and DEFAULT_RATE_LIMIT to SettingsService.
- [ ] 2. Add getRateLimit and setRateLimit methods to SettingsService.
- [ ] 3. Add rateLimitField to SettingsView.xml in the Conversation Settings tab.
- [ ] 4. Add rateLimitField @Outlet to SettingsView.java.
- [ ] 5. Initialize rateLimitField in SettingsView.initSettings.
- [ ] 6. Save rateLimitField value in SettingsView.onSaveSettings.

# Implementation Progress
- [x] Add KEY_RATE_LIMIT and DEFAULT_RATE_LIMIT to SettingsService.
- [x] Add getRateLimit and setRateLimit methods to SettingsService.
- [x] Add rateLimitField to SettingsView.xml in the Conversation Settings tab.
- [x] Add rateLimitField @Outlet to SettingsView.java.
- [x] Initialize rateLimitField in SettingsView.initSettings.
- [x] Save rateLimitField value in SettingsView.onSaveSettings.

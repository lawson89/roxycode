# Goal
Add a new setting to specify the maximum requests per minute (rate limit) for the Gemini API.

# Proposed Changes
- Update SettingsService to include a rate limit setting with a default value.
- Update SettingsView.xml to include a field for the rate limit setting.
- Update SettingsView.java to handle loading and saving the new rate limit setting.

# Implementation Steps
- [ ] Add KEY_RATE_LIMIT and DEFAULT_RATE_LIMIT to SettingsService.
- [ ] Add getRateLimit and setRateLimit methods to SettingsService.
- [ ] Add rateLimitField to SettingsView.xml in the Global Settings tab.
- [ ] Add rateLimitField @Outlet to SettingsView.java.
- [ ] Initialize rateLimitField in SettingsView.initSettings.
- [ ] Save rateLimitField value in SettingsView.onSaveSettings.

# Implementation Progress

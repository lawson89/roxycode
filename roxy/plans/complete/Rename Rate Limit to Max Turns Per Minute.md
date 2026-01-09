# Goal
Rename the 'Rate Limit' setting to 'Max Turns Per Minute' and update its default value to 30.

# Proposed Changes
- Update SettingsService.java: Rename constants and methods related to Rate Limit to Max Turns Per Minute, and change default to 30.
- Update SettingsView.xml: Update label text and field name.
- Update SettingsView.java: Update field name and method calls.

# Implementation Steps
- [ ] Create the plan and switch to CODE mode.
- [ ] Update SettingsService.java constants and methods.
- [ ] Update SettingsView.xml label and field ID.
- [ ] Update SettingsView.java field name and logic.
- [ ] Compile and verify.

# Implementation Progress
- [x] [x] Rename KEY_RATE_LIMIT and DEFAULT_RATE_LIMIT in SettingsService
- [x] [x] Rename getRateLimit and setRateLimit in SettingsService
- [x] [x] Update SettingsView.xml label and field ID
- [x] [x] Update SettingsView.java to use maxTurnsPerMinuteField and new service methods
- [x] [x] Verify compilation and run tests
- [x] Completed: All changes applied and verified.

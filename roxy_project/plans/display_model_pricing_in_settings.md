# Plan: Display Model Pricing in Settings View

The goal is to display the input and output pricing for each Gemini model in the Settings view of RoxyCode. This will help users understand the cost associated with the selected model.

## Proposed Changes

### UI Changes
- **SettingsView.xml**: Add labels to display "Input Price" and "Output Price" below the model selection dropdown.

### Core Changes
- **GeminiModel.java**: Add fields for `inputPrice` and `outputPrice` to the `GeminiModel` class and its builder. These should represent the price per token (or 1M tokens, as per Gemini's pricing structure).
- **models.toml**: Add the pricing data for each model.

### UI Integration
- **MainFrame.java**: Update the `initSettings` method to load and display the pricing for the currently selected model.
- **MainFrame.java**: Add an `ActionListener` to the model selection dropdown to update the pricing labels dynamically when a different model is selected.

## Checklist
- [x] Modify `SettingsView.xml` to include labels for input and output prices.
- [x] Update `GeminiModel.java` to expose pricing fields and update `models.toml` with pricing data.
- [x] Modify `MainFrame.java` to handle model selection changes and update the price labels.

## Implementation Progress
- [x] Analyze `SettingsView.xml` and `MainFrame.java`.
- [x] Update `SettingsView.xml` with pricing labels.
- [x] Update `GeminiModel.java` with pricing fields.
- [x] Update `models.toml` with pricing information.
- [x] Update `MainFrame.java` to refresh labels on model change.
- [x] Verify changes with build and tests.

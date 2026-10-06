package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.SettingsService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptPanelTest {

    @Mock
    private PromptService promptService;

    @Mock
    private SettingsService settingsService;

    @Test
    void testPromptPanelInstantiation() {
        when(settingsService.getSettings()).thenReturn(new AppSettings());
        PromptPanel panel = new PromptPanel(promptService, settingsService);
        assertNotNull(panel);
    }
}

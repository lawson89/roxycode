package org.roxycode.app.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AppSettingsTest {

    @Test
    void testMaxDiscoveryTurns() {
        AppSettings settings = new AppSettings();
        assertEquals(5, settings.getMaxDiscoveryTurns());
        settings.setMaxDiscoveryTurns(10);
        assertEquals(10, settings.getMaxDiscoveryTurns());
    }
}
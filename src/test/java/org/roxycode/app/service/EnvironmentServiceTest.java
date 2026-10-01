package org.roxycode.app.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentServiceTest {

    private final EnvironmentService service = new EnvironmentService();

    @Test
    void testGetSystemInfo() {
        assertNotNull(service.getOsName());
        assertNotNull(service.getJavaVersion());
        assertNotNull(service.getCurrentUser());
    }

    @Test
    void testMemoryUsage() {
        assertTrue(service.getTotalMemoryMb() > 0);
        assertTrue(service.getMemoryUsageMb() >= 0);
    }

    @Test
    void testGetOsIcon() {
        // Note: Headless environment check
        assertNotNull(service.getOsIcon());
    }
}
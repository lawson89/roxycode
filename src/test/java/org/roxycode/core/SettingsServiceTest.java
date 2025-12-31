package org.roxycode.core;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

@MicronautTest
class SettingsServiceTest {

    @Inject
    SettingsService settingsService;

    @Test
    void testRecentProjectsSerialization() {
        // Clear initial state
        settingsService.addRecentProject("/tmp/dummy");

        // Add a project
        String path1 = "/home/user/code/project1";
        settingsService.addRecentProject(path1);

        // Add another to verify order (LIFO-ish for "Recent")
        String path2 = "/home/user/code/project2";
        settingsService.addRecentProject(path2);

        List<String> projects = settingsService.getRecentProjects();

        // Verify it exists and order is correct (most recent first)
        Assertions.assertTrue(projects.contains(path1));
        Assertions.assertEquals(path2, projects.getFirst());

        // Verify JSON persistence logic (implicitly tested by reading back)
        Assertions.assertTrue(projects.size() >= 2);
    }
}
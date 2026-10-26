package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.events.ProjectChangedEvent;

@ExtendWith(MockitoExtension.class)
class ProjectAnalysisServiceTest {

    @Mock private GrepService grepService;

    @InjectMocks private ProjectAnalysisService projectAnalysisService;

    @Test
    void testGetDominantLanguageJava() {
        List<String> files =
                Arrays.asList("src/Main.java", "src/Util.java", "README.md", "pom.xml");
        when(grepService.listFiles(null)).thenReturn(files);

        assertEquals("Java", projectAnalysisService.getDominantLanguage());
    }

    @Test
    void testGetDominantLanguagePython() {
        List<String> files = Arrays.asList("app.py", "utils.py", "requirements.txt");
        when(grepService.listFiles(null)).thenReturn(files);

        assertEquals("Python", projectAnalysisService.getDominantLanguage());
    }

    @Test
    void testGetDominantLanguageUnknown() {
        List<String> files = Arrays.asList("config.conf", "data.dat");
        when(grepService.listFiles(null)).thenReturn(files);

        assertEquals("Unknown (.conf)", projectAnalysisService.getDominantLanguage());
    }

    @Test
    void testGetDominantLanguageEmpty() {
        when(grepService.listFiles(null)).thenReturn(Collections.emptyList());

        assertEquals("Unknown", projectAnalysisService.getDominantLanguage());
    }

    @Test
    void testGetDominantLanguageNull() {
        when(grepService.listFiles(null)).thenReturn(null);

        assertEquals("Unknown", projectAnalysisService.getDominantLanguage());
    }

    @Test
    void testGetDominantLanguageHiddenFiles() {
        List<String> files =
                Arrays.asList(
                        ".gitignore",
                        "dir/.env",
                        "dir/trailingdot.",
                        "Makefile",
                        "   ",
                        "script.sh");
        when(grepService.listFiles(null)).thenReturn(files);

        assertEquals("Shell", projectAnalysisService.getDominantLanguage());
    }

    @Test
    void testDominantLanguageCachedAndInvalidatedOnProjectChangedEvent() {
        when(grepService.listFiles(null))
                .thenReturn(List.of("src/Main.java"))
                .thenReturn(List.of("app.py"));

        assertEquals("Java", projectAnalysisService.getDominantLanguage());
        assertEquals("Java", projectAnalysisService.getDominantLanguage());
        verify(grepService, times(1)).listFiles(null);

        projectAnalysisService.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new-proj")));

        assertEquals("Python", projectAnalysisService.getDominantLanguage());
        verify(grepService, times(2)).listFiles(null);
    }
}

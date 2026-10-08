package org.roxycode.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.services.GrepService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectAnalysisServiceTest {

    @Mock
    private GrepService grepService;

    @InjectMocks
    private ProjectAnalysisService projectAnalysisService;

    @Test
    void testGetDominantLanguageJava() {
        List<String> files = Arrays.asList(
                "src/Main.java",
                "src/Util.java",
                "README.md",
                "pom.xml"
        );
        when(grepService.listFiles(null)).thenReturn(files);

        assertEquals("Java", projectAnalysisService.getDominantLanguage());
    }

    @Test
    void testGetDominantLanguagePython() {
        List<String> files = Arrays.asList(
                "app.py",
                "utils.py",
                "requirements.txt"
        );
        when(grepService.listFiles(null)).thenReturn(files);

        assertEquals("Python", projectAnalysisService.getDominantLanguage());
    }

    @Test
    void testGetDominantLanguageUnknown() {
        List<String> files = Arrays.asList(
                "config.conf",
                "data.dat"
        );
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
        List<String> files = Arrays.asList(
                ".gitignore",
                "script.sh"
        );
        when(grepService.listFiles(null)).thenReturn(files);

        assertEquals("Shell", projectAnalysisService.getDominantLanguage());
    }
}
package org.roxycode.core.service;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.roxycode.core.tools.service.TikaService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@MicronautTest
class TikaServiceTest {

    @Inject
    TikaService tikaService;

    @Test
    void testExtractTextFromPlainString() throws IOException {
        String content = "Hello Tika World";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        
        String result = tikaService.extractText(inputStream);
        
        // Tika usually adds a newline or wraps it
        Assertions.assertTrue(result.contains("Hello Tika World"));
    }
    
    @Test
    void testExtractMetadata() throws IOException {
        String content = "Hello Metadata";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        
        TikaService.ExtractionResult result = tikaService.extractAll(inputStream);
        
        Assertions.assertTrue(result.text().contains("Hello Metadata"));
        Assertions.assertNotNull(result.metadata());
        // Expecting some metadata like Content-Type or encoding
        Assertions.assertFalse(result.metadata().isEmpty(), "Metadata should not be empty");
        // For text, it usually detects Content-Type
        Assertions.assertTrue(result.metadata().containsKey("Content-Type"));
    }
}

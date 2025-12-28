package org.roxycode.core.service;

import java.io.InputStream;
import java.io.IOException;
import java.util.Map;

public interface TikaService {
    /**
     * Extracts text content from a document.
     */
    String extractText(InputStream inputStream) throws IOException;

    /**
     * Extracts text content and metadata from a document.
     */
    ExtractionResult extractAll(InputStream inputStream) throws IOException;

    record ExtractionResult(String text, Map<String, String> metadata) {}
}

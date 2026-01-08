package org.roxycode.core.tools.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.roxycode.core.tools.ScriptService;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for extracting text and metadata from various file formats using Apache Tika.
 */
@ScriptService("tikaService")
@Singleton
public class TikaService {

    private final Sandbox sandbox;

    @Inject
    public TikaService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * Extracts text content from an input stream.
     *
     * @param inputStream The input stream of the document.
     * @return The extracted text content.
     * @throws IOException If an I/O error occurs or parsing fails.
     */
    @LLMDoc("Extracts text content from an input stream using Tika")
    public String extractText(InputStream inputStream) throws IOException {
        return extractAll(inputStream).text();
    }

    /**
     * Reads a document from a path and extracts its text content.
     *
     * @param pathStr The path to the document file.
     * @return The extracted text content.
     * @throws IOException If the file is not found, is a directory, or parsing fails.
     */
    @LLMDoc("Reads a document from a path and extracts its text content using Tika")
    public String readDocument(String pathStr) throws IOException {
        Path path = sandbox.resolve(pathStr);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + pathStr);
        }
        if (Files.isDirectory(path)) {
            throw new IOException("Path is a directory: " + pathStr);
        }
        try (InputStream is = Files.newInputStream(path)) {
            return extractText(is);
        }
    }

    /**
     * Extracts both text and metadata from an input stream.
     *
     * @param inputStream The input stream of the document.
     * @return An ExtractionResult containing the text and a map of metadata.
     * @throws IOException If an I/O error occurs or parsing fails.
     */
    @LLMDoc("Extracts both text and metadata from an input stream using Tika")
    public ExtractionResult extractAll(InputStream inputStream) throws IOException {
        Parser parser = new AutoDetectParser();
        // BodyContentHandler(-1) disables the write limit, allowing large documents
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        try {
            parser.parse(inputStream, handler, metadata, context);
        } catch (SAXException | TikaException e) {
            throw new IOException("Failed to parse document", e);
        }

        Map<String, String> metaMap = new HashMap<>();
        for (String name : metadata.names()) {
            metaMap.put(name, metadata.get(name));
        }

        return new ExtractionResult(handler.toString(), metaMap);
    }

    /**
     * Result of a Tika extraction operation.
     *
     * @param text     The extracted text content.
     * @param metadata A map of extracted metadata keys and values.
     */
    public record ExtractionResult(String text, Map<String, String> metadata) {}
}

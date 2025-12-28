package org.roxycode.core.service;

import jakarta.inject.Singleton;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Singleton
public class TikaServiceImpl implements TikaService {

    @Override
    public String extractText(InputStream inputStream) throws IOException {
        return extractAll(inputStream).text();
    }

    @Override
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
}

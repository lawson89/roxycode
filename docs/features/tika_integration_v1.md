# Feature Plan: Tika Integration v1
**Date:** 2025-12-28
**Status:** Draft

## 1. Objective
Implement a `TikaService` to provide document text and metadata extraction capabilities within the RoxyCode platform. The initial focus will be on robust PDF text extraction, leveraging Apache Tika's comprehensive parsing capabilities.

## 2. Dependencies
To support Tika, the following dependencies will be added to `pom.xml`:

```xml
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>3.2.3</version>
</dependency>
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>3.2.3</version>
</dependency>
```

## 3. Architecture & Design

### 3.1 TikaService Interface
A new service interface will be defined in `org.roxycode.core.service`.

```java
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
```

### 3.2 Implementation
The implementation `TikaServiceImpl` will use the `AutoDetectParser` from Apache Tika to handle various file formats automatically, with specific configuration/tuning for PDFs if needed (e.g., handling OCR via Tesseract if available, though not required for v1).

## 4. Implementation Plan

### Phase 1: Infrastructure (Foundation)
- [ ] Add Apache Tika dependencies to `pom.xml`.
- [ ] Define `TikaService` interface and `ExtractionResult` record.
- [ ] Implement `TikaServiceImpl` using `AutoDetectParser`.
- [ ] Register `TikaServiceImpl` as a Micronaut `@Singleton`.

### Phase 2: PDF Focus
- [ ] Verify PDF extraction functionality with sample PDF documents.
- [ ] Implement unit tests in `src/test/java/org/roxycode/core/service/TikaServiceTest.java`.
- [ ] Handle common PDF extraction issues (e.g., encrypted PDFs, password protection).

### Phase 3: Integration
- [ ] Integrate `TikaService` into the existing file processing pipeline or UI (if applicable).
- [ ] Provide a CLI command or tool to test extraction on local files.

## 5. Success Criteria
- [ ] `TikaService` can successfully extract text from standard PDF documents.
- [ ] Metadata (e.g., Author, Title, Creation Date) is correctly extracted from PDFs.
- [ ] The service is integrated into the Micronaut dependency injection container.
- [ ] Unit tests cover various PDF scenarios (standard, multi-page, etc.).

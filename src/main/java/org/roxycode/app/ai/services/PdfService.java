package org.roxycode.app.ai.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.ExceptionUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Provides stateless PDF discovery, transcription, extraction, and summarization using {@code gemini-3.8-flash} across
 * project directories and mounted {@code @<alias>} read-only reference folders without polluting chat memory.
 */
@Service
@AgentService(value = "pdfService", phases = {"*"})
@AgentDoc("Provides stateless PDF discovery, transcription, extraction, and summarization using gemini-3.8-flash across project directories and mounted @<alias> reference folders without polluting chat memory.")
public class PdfService {

    /** Maximum supported PDF file size in bytes (20 MB). */
    public static final long MAX_PDF_BYTES = 20L * 1024L * 1024L;
    static final int BATCH_CONCURRENCY_LIMIT = 4;
    static final int MAX_BATCH_PDFS = 25;

    private static final byte[] PDF_MAGIC_HEADER = new byte[]{'%', 'P', 'D', 'F', '-'};
    private static final String ERROR_PREFIX = "Error: ";
    private static final double BYTES_PER_MB = 1024.0 * 1024.0;
    private static final String DOC_PDF_PATH = "The PDF file path relative to the project root or a mounted @<alias> reference folder";
    private static final String DOC_PDF_PATHS = "Array or list of PDF file paths relative to the project root or mounted @<alias> reference folders";
    private static final String DOC_PROMPT = "Custom extraction or analysis instructions (optional; defaults to structured Markdown extraction)";

    private final ProjectService projectService;
    private final GrepService grepService;
    private final AiService aiService;

    /**
     * Creates a new {@code PdfService}.
     *
     * @param projectService
     *            the project path and reference-folder resolution service
     * @param grepService
     *            the file discovery service
     * @param aiService
     *            the AI orchestration service (lazily injected)
     */
    public PdfService(ProjectService projectService, GrepService grepService, @Lazy AiService aiService) {
        this.projectService = projectService;
        this.grepService = grepService;
        this.aiService = aiService;
    }

    /**
     * Lists all PDF files across the project root (excluding gitignored directories).
     *
     * @return deterministically sorted list of project-relative PDF paths
     */
    @AgentDoc(value = "Lists all .pdf files across the project root (respecting .gitignore).", examples = "pdfService.listPdfs()")
    public List<String> listPdfs() {
        return listPdfs(".");
    }

    /**
     * Lists all PDF files under the specified project subdirectory or mounted {@code @<alias>} reference folder.
     *
     * @param relativePath
     *            the directory path relative to the project root or {@code @<alias>} reference folder
     * @return deterministically sorted list of relative PDF paths
     */
    @AgentDoc(value = "Lists all .pdf files in a specific project directory or mounted @<alias> reference folder.", examples = {
            "pdfService.listPdfs('docs')", "pdfService.listPdfs('@specs')"})
    public List<String> listPdfs(
            @AgentDoc("The directory path relative to the project root or a mounted @<alias> reference folder") @Nullable String relativePath) {
        String effectivePath = StringUtils.defaultIfBlank(StringUtils.trimToNull(relativePath), ".");
        List<String> files = grepService.listFiles(effectivePath);
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        Set<String> pdfSet = new LinkedHashSet<>();
        for (String file : files) {
            if (file != null && file.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                pdfSet.add(file);
            }
        }
        List<String> sortedPdfs = new ArrayList<>(pdfSet);
        Collections.sort(sortedPdfs);
        return sortedPdfs;
    }

    /**
     * Extracts and summarizes a PDF file in structured Markdown using {@code gemini-3.8-flash}.
     *
     * @param relativePath
     *            the PDF file path relative to the project root or mounted {@code @<alias>} reference folder
     * @return the analysis result
     */
    @AgentDoc(value = "Analyzes and extracts structured Markdown (headings, tables, diagrams, [Page N] citations) from a PDF up to 20 MB using stateless gemini-3.8-flash.", examples = {
            "pdfService.analyzePdf('docs/architecture.pdf')", "pdfService.analyzePdf('@specs/api.pdf')"})
    public EditorResult analyzePdf(@AgentDoc(DOC_PDF_PATH) String relativePath) {
        return analyzePdf(relativePath, null);
    }

    /**
     * Analyzes a PDF file with custom instructions using stateless {@code gemini-3.8-flash}.
     *
     * @param relativePath
     *            the PDF file path relative to the project root or mounted {@code @<alias>} reference folder
     * @param prompt
     *            custom extraction or analysis instructions (defaults to structured Markdown if blank)
     * @return the analysis result
     */
    @AgentDoc(value = "Analyzes a PDF up to 20 MB with custom instructions using stateless gemini-3.8-flash without polluting chat memory.", examples = {
            "pdfService.analyzePdf('docs/manual.pdf', 'Extract pages 1-20 error codes as a Markdown table.')",
            "pdfService.analyzePdf('@specs/rfc.pdf', 'Summarize section 4 requirements.')"})
    public EditorResult analyzePdf(@AgentDoc(DOC_PDF_PATH) String relativePath,
            @AgentDoc(DOC_PROMPT) @Nullable String prompt) {
        if (StringUtils.isBlank(relativePath)) {
            return new EditorResult(false, "", "PDF file path must not be blank.");
        }
        ExceptionUtils.checkInterrupted("PDF analysis cancelled before start");
        Path path;
        try {
            path = projectService.resolveReadPath(relativePath);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            return new EditorResult(false, "", e.getMessage());
        }
        if (!Files.exists(path)) {
            return new EditorResult(false, "", "PDF file does not exist: " + relativePath);
        }
        if (Files.isDirectory(path)) {
            return new EditorResult(false, "", "Path is a directory, not a PDF file: " + relativePath);
        }
        if (!Files.isRegularFile(path)) {
            return new EditorResult(false, "", "Path is not a regular file: " + relativePath);
        }
        try {
            long fileSize = Files.size(path);
            if (fileSize <= 0) {
                return new EditorResult(false, "", "PDF file is empty: " + relativePath);
            }
            if (fileSize > MAX_PDF_BYTES) {
                String sizeMsg = String.format(Locale.ROOT, "PDF file exceeds 20 MB limit (%.2f MB): %s",
                        fileSize / BYTES_PER_MB, relativePath);
                return new EditorResult(false, "", sizeMsg);
            }
            byte[] pdfBytes = Files.readAllBytes(path);
            if (!hasPdfMagicHeader(pdfBytes)) {
                return new EditorResult(false, "", "File does not have a valid %PDF- header: " + relativePath);
            }
            String displayPath = projectService.formatReadDisplayPath(path, relativePath);
            String content = aiService.analyzePdf(pdfBytes, displayPath, prompt);
            if (StringUtils.isBlank(content)) {
                return new EditorResult(false, "", "PDF analysis returned an empty response for: " + displayPath);
            }
            return new EditorResult(true, content, "");
        } catch (IOException e) {
            return new EditorResult(false, "", "Failed to read PDF file '" + relativePath + "': " + e.getMessage());
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, "PDF analysis cancelled");
            return new EditorResult(false, "", "PDF analysis failed: " + ExceptionUtils.getRootCauseMessage(e));
        }
    }

    /**
     * Analyzes multiple PDF files concurrently (bounded to 4 simultaneous calls) while preserving input order.
     *
     * @param relativePaths
     *            the PDF file paths to analyze
     * @param prompt
     *            custom extraction or analysis instructions applied to each PDF
     * @return ordered map of path to extracted Markdown content or {@code "Error: ..."} message
     */
    @AgentDoc(value = "Analyzes multiple PDFs concurrently (up to 4 in flight, max 25 per batch) using stateless gemini-3.8-flash, returning a map of path to Markdown or 'Error: ...' in input order.", examples = "pdfService.analyzePdfs(['docs/a.pdf', 'docs/b.pdf'], 'Summarize key architecture decisions.')")
    public Map<String, String> analyzePdfs(@AgentDoc(DOC_PDF_PATHS) String[] relativePaths,
            @AgentDoc(DOC_PROMPT) @Nullable String prompt) {
        if (relativePaths == null || relativePaths.length == 0) {
            return Map.of();
        }
        return analyzePdfs(Arrays.asList(relativePaths), prompt);
    }

    /**
     * Analyzes multiple PDF files concurrently (bounded to 4 simultaneous calls) while preserving input order.
     *
     * @param relativePaths
     *            the list of PDF file paths to analyze
     * @param prompt
     *            custom extraction or analysis instructions applied to each PDF
     * @return ordered map of path to extracted Markdown content or {@code "Error: ..."} message
     */
    @AgentDoc(value = "Analyzes a list of PDFs concurrently (up to 4 in flight, max 25 per batch) using stateless gemini-3.8-flash, returning a map of path to Markdown or 'Error: ...' in input order.", examples = "pdfService.analyzePdfs(pdfService.listPdfs('docs'), 'Extract API endpoints and schemas.')")
    public Map<String, String> analyzePdfs(@AgentDoc(DOC_PDF_PATHS) List<String> relativePaths,
            @AgentDoc(DOC_PROMPT) @Nullable String prompt) {
        if (relativePaths == null || relativePaths.isEmpty()) {
            return Map.of();
        }
        List<String> uniquePaths = deduplicateNonBlankPaths(relativePaths);
        if (uniquePaths.isEmpty()) {
            return Map.of();
        }
        int totalCount = uniquePaths.size();
        int batchCount = Math.min(totalCount, MAX_BATCH_PDFS);
        List<String> targetPaths = uniquePaths.subList(0, batchCount);
        Semaphore semaphore = new Semaphore(BATCH_CONCURRENCY_LIMIT);
        Map<String, Future<EditorResult>> futures = new LinkedHashMap<>();
        Map<String, String> results = new LinkedHashMap<>();
        for (String path : targetPaths) {
            futures.put(path, CompletableFuture.supplyAsync(() -> analyzeWithPermit(semaphore, path, prompt),
                    Edt.VIRTUAL_EXECUTOR));
        }
        try {
            for (Map.Entry<String, Future<EditorResult>> entry : futures.entrySet()) {
                results.put(entry.getKey(), resolveFutureResult(entry.getValue()));
            }
        } finally {
            for (Future<EditorResult> future : futures.values()) {
                future.cancel(true);
            }
        }
        if (totalCount > MAX_BATCH_PDFS) {
            results.put("_truncated", "Showing first " + MAX_BATCH_PDFS + " of " + totalCount + " PDFs.");
        }
        return results;
    }

    private EditorResult analyzeWithPermit(Semaphore semaphore, String path, @Nullable String prompt) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ExceptionUtils.asCancellationException(e,
                    "PDF batch analysis interrupted while waiting for concurrency permit");
        }
        try {
            return analyzePdf(path, prompt);
        } finally {
            semaphore.release();
        }
    }

    private static String resolveFutureResult(Future<EditorResult> future) {
        try {
            EditorResult result = future.get();
            if (result.success()) {
                return result.content();
            }
            return StringUtils.prependIfMissing(result.errorHint(), ERROR_PREFIX);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ExceptionUtils.asCancellationException(e, "PDF batch analysis interrupted");
        } catch (ExecutionException e) {
            ExceptionUtils.rethrowIfCancelled(e, "PDF batch analysis cancelled");
            return ERROR_PREFIX + ExceptionUtils.getRootCauseMessage(e);
        }
    }

    private static List<String> deduplicateNonBlankPaths(List<String> relativePaths) {
        Set<String> seen = new LinkedHashSet<>();
        for (String rawPath : relativePaths) {
            if (StringUtils.isNotBlank(rawPath)) {
                seen.add(rawPath.trim());
            }
        }
        return new ArrayList<>(seen);
    }

    static boolean hasPdfMagicHeader(byte[] bytes) {
        if (bytes == null || bytes.length < PDF_MAGIC_HEADER.length) {
            return false;
        }
        return Arrays.equals(bytes, 0, PDF_MAGIC_HEADER.length, PDF_MAGIC_HEADER, 0, PDF_MAGIC_HEADER.length);
    }
}

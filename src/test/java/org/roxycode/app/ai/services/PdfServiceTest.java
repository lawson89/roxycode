package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

class PdfServiceTest {

    @TempDir
    Path tempDir;

    @TempDir
    Path refDir;

    private ProjectService projectService;
    private GrepService grepService;
    private AiService aiService;
    private PdfService pdfService;

    @BeforeEach
    void setUp() {
        SettingsService settingsService = mock(SettingsService.class);
        lenient().when(settingsService.getSettings()).thenReturn(new org.roxycode.app.model.AppSettings());
        projectService = spy(new ProjectService(settingsService, null));
        lenient().when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        lenient().when(projectService.getProjectReferenceFolders()).thenReturn(List.of(refDir.toString()));
        lenient().when(projectService.getGlobalReferenceFolders()).thenReturn(List.of());
        grepService = mock(GrepService.class);
        aiService = mock(AiService.class);
        pdfService = new PdfService(projectService, grepService, aiService);
    }

    private Path writeValidPdf(Path dir, String relPath, String body) throws IOException {
        Path target = dir.resolve(relPath);
        Files.createDirectories(target.getParent());
        byte[] bytes = ("%PDF-1.7\n" + body).getBytes(StandardCharsets.US_ASCII);
        return Files.write(target, bytes);
    }

    @Test
    void testListPdfsFiltersCaseInsensitivelyAndSortsDeterministically() {
        when(grepService.listFiles("."))
                .thenReturn(List.of("src/App.java", "docs/z.PDF", "docs/a.pdf", "docs/a.pdf", "notes.txt"));
        assertEquals(List.of("docs/a.pdf", "docs/z.PDF"), pdfService.listPdfs());

        String refAlias = projectService.getReferenceFolderMounts().get(0).alias();
        when(grepService.listFiles("@" + refAlias)).thenReturn(
                List.of("@" + refAlias + "/b.pdf", "@" + refAlias + "/README.md", "@" + refAlias + "/a.pdf"));
        assertEquals(List.of("@" + refAlias + "/a.pdf", "@" + refAlias + "/b.pdf"),
                pdfService.listPdfs("@" + refAlias));

        when(grepService.listFiles("empty")).thenReturn(List.of());
        assertTrue(pdfService.listPdfs("empty").isEmpty());
    }

    @Test
    void testAnalyzePdfValidationGuardsAndCancellation() throws IOException {
        assertFalse(pdfService.analyzePdf("   ").success());
        assertFalse(pdfService.analyzePdf("missing.pdf").success());

        Files.createDirectories(tempDir.resolve("folder.pdf"));
        EditorResult dirResult = pdfService.analyzePdf("folder.pdf");
        assertFalse(dirResult.success());
        assertTrue(dirResult.errorHint().contains("directory"));

        Files.write(tempDir.resolve("empty.pdf"), new byte[0]);
        EditorResult emptyResult = pdfService.analyzePdf("empty.pdf");
        assertFalse(emptyResult.success());
        assertTrue(emptyResult.errorHint().contains("empty"));

        Files.writeString(tempDir.resolve("fake.pdf"), "NOT A PDF FILE");
        EditorResult headerResult = pdfService.analyzePdf("fake.pdf");
        assertFalse(headerResult.success());
        assertTrue(headerResult.errorHint().contains("%PDF-"));

        Path oversized = tempDir.resolve("huge.pdf");
        try (RandomAccessFile raf = new RandomAccessFile(oversized.toFile(), "rw")) {
            raf.setLength(PdfService.MAX_PDF_BYTES + 1L);
        }
        EditorResult sizeResult = pdfService.analyzePdf("huge.pdf");
        assertFalse(sizeResult.success());
        assertTrue(sizeResult.errorHint().contains("20 MB"));

        EditorResult escapeResult = pdfService.analyzePdf("../outside.pdf");
        assertFalse(escapeResult.success());
        assertTrue(escapeResult.errorHint().contains("Path traversal attempt detected"));

        writeValidPdf(tempDir, "docs/blank-ai.pdf", "content");
        when(aiService.analyzePdf(any(byte[].class), eq("docs/blank-ai.pdf"), isNull())).thenReturn("   ");
        EditorResult blankAiResult = pdfService.analyzePdf("docs/blank-ai.pdf");
        assertFalse(blankAiResult.success());
        assertTrue(blankAiResult.errorHint().contains("empty response"));

        writeValidPdf(tempDir, "docs/cancel.pdf", "content");
        when(aiService.analyzePdf(any(byte[].class), eq("docs/cancel.pdf"), anyString()))
                .thenThrow(new CancellationException("User stopped"));
        assertThrows(CancellationException.class, () -> pdfService.analyzePdf("docs/cancel.pdf", "Extract"));
    }

    @Test
    void testAnalyzePdfSuccessWithProjectAndReferenceFolders() throws IOException {
        writeValidPdf(tempDir, "docs/spec.pdf", "project spec");
        when(aiService.analyzePdf(any(byte[].class), eq("docs/spec.pdf"), isNull()))
                .thenReturn("# Spec Summary\n\n- Requirement 1 [Page 1]");

        EditorResult defaultRes = pdfService.analyzePdf("docs/spec.pdf");
        assertTrue(defaultRes.success());
        assertEquals("# Spec Summary\n\n- Requirement 1 [Page 1]", defaultRes.content());

        String refAlias = projectService.getReferenceFolderMounts().get(0).alias();
        writeValidPdf(refDir, "manual.pdf", "external manual");
        String refDisplayPath = "@" + refAlias + "/manual.pdf";
        when(aiService.analyzePdf(any(byte[].class), eq(refDisplayPath), eq("Extract tables")))
                .thenReturn("| Col |\n| --- |\n| Val |");

        EditorResult refRes = pdfService.analyzePdf(refDisplayPath, "Extract tables");
        assertTrue(refRes.success());
        assertEquals("| Col |\n| --- |\n| Val |", refRes.content());
    }

    @Test
    void testAnalyzePdfsBoundedConcurrencyOrderPreservationAndResilience() throws IOException {
        List<String> inputPaths = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String path = "batch/doc-" + i + ".pdf";
            writeValidPdf(tempDir, path, "body-" + i);
            inputPaths.add(path);
        }
        inputPaths.add("batch/doc-0.pdf");
        inputPaths.add("   ");
        inputPaths.add("batch/missing.pdf");

        AtomicInteger activeCount = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        when(aiService.analyzePdf(any(byte[].class), anyString(), eq("Summarize"))).thenAnswer(inv -> {
            int current = activeCount.incrementAndGet();
            maxObserved.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(35L);
                String displayPath = inv.getArgument(1);
                return "Summary of " + displayPath;
            } finally {
                activeCount.decrementAndGet();
            }
        });

        Map<String, String> listResults = pdfService.analyzePdfs(inputPaths, "Summarize");
        assertEquals(7, listResults.size());
        assertTrue(maxObserved.get() <= PdfService.BATCH_CONCURRENCY_LIMIT,
                "Expected at most 4 concurrent PDF calls, got " + maxObserved.get());
        assertEquals(List.of("batch/doc-0.pdf", "batch/doc-1.pdf", "batch/doc-2.pdf", "batch/doc-3.pdf",
                "batch/doc-4.pdf", "batch/doc-5.pdf", "batch/missing.pdf"), new ArrayList<>(listResults.keySet()));
        assertEquals("Summary of batch/doc-0.pdf", listResults.get("batch/doc-0.pdf"));
        assertTrue(listResults.get("batch/missing.pdf").startsWith("Error: "));

        Map<String, String> arrayResults = pdfService.analyzePdfs(new String[]{"batch/doc-1.pdf", "batch/doc-2.pdf"},
                "Summarize");
        assertEquals(List.of("batch/doc-1.pdf", "batch/doc-2.pdf"), new ArrayList<>(arrayResults.keySet()));
        assertTrue(pdfService.analyzePdfs(new String[0], "Summarize").isEmpty());
    }
}

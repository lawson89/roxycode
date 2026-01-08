package org.roxycode.ui.views;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.GenAIService;
import org.roxycode.core.SettingsService;
import org.roxycode.core.SlashCommandService;
import org.roxycode.core.cache.ProjectCacheMetaService;
import org.roxycode.ui.ThemeService;

import javax.swing.*;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatViewDndTest {

    @TempDir
    Path tempDir;

    @Test
    void testCanImportFiles() {
        // Mock dependencies
        GenAIService genAIService = mock(GenAIService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ThemeService themeService = mock(ThemeService.class);
        SlashCommandService slashCommandService = mock(SlashCommandService.class);
        ProjectCacheMetaService projectCacheMetaService = mock(ProjectCacheMetaService.class);

        ChatView chatView = new ChatView(genAIService, settingsService, themeService, slashCommandService, projectCacheMetaService);
        ChatView.FileTransferHandler handler = chatView.new FileTransferHandler(null);
        
        TransferHandler.TransferSupport support = mock(TransferHandler.TransferSupport.class);
        when(support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)).thenReturn(true);
        
        boolean canImport = handler.canImport(support);
        
        assertTrue(canImport);
        verify(support).setDropAction(TransferHandler.COPY);
    }

    @Test
    void testImportData() throws Exception {
        // Create a real file
        File file = tempDir.resolve("test.txt").toFile();
        file.createNewFile();

        // Mock dependencies
        GenAIService genAIService = mock(GenAIService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ThemeService themeService = mock(ThemeService.class);
        SlashCommandService slashCommandService = mock(SlashCommandService.class);
        ProjectCacheMetaService projectCacheMetaService = mock(ProjectCacheMetaService.class);

        ChatView chatView = new ChatView(genAIService, settingsService, themeService, slashCommandService, projectCacheMetaService);
        ChatView.FileTransferHandler handler = chatView.new FileTransferHandler(null);
        
        TransferHandler.TransferSupport support = mock(TransferHandler.TransferSupport.class);
        java.awt.datatransfer.Transferable transferable = mock(java.awt.datatransfer.Transferable.class);
        
        when(support.getTransferable()).thenReturn(transferable);
        when(support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)).thenReturn(true);
        
        java.util.List<java.io.File> files = java.util.List.of(file);
        when(transferable.getTransferData(DataFlavor.javaFileListFlavor)).thenReturn(files);
        
        boolean imported = handler.importData(support);
        
        assertTrue(imported);
        assertTrue(chatView.getAttachedFiles().contains(file));
    }


    @Test
    void testImportDataUriList() throws Exception {
        // Create real files
        File file1 = tempDir.resolve("test1.txt").toFile();
        file1.createNewFile();
        File file2 = tempDir.resolve("test2.txt").toFile();
        file2.createNewFile();

        // Mock dependencies
        GenAIService genAIService = mock(GenAIService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ThemeService themeService = mock(ThemeService.class);
        SlashCommandService slashCommandService = mock(SlashCommandService.class);
        ProjectCacheMetaService projectCacheMetaService = mock(ProjectCacheMetaService.class);

        ChatView chatView = new ChatView(genAIService, settingsService, themeService, slashCommandService, projectCacheMetaService);
        ChatView.FileTransferHandler handler = chatView.new FileTransferHandler(null);
        
        TransferHandler.TransferSupport support = mock(TransferHandler.TransferSupport.class);
        java.awt.datatransfer.Transferable transferable = mock(java.awt.datatransfer.Transferable.class);
        
        when(support.getTransferable()).thenReturn(transferable);
        
        // javaFileListFlavor NOT supported
        when(support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)).thenReturn(false);
        
        // Flexible matching for uri-list
        when(support.isDataFlavorSupported(any(DataFlavor.class))).thenAnswer(inv -> {
            DataFlavor flavor = inv.getArgument(0);
            return flavor.getMimeType().startsWith("text/uri-list");
        });
        
        String uriList = file1.toURI().toString() + "\r\n#comment\r\n" + file2.toURI().toString();
        
        when(transferable.getTransferData(any(DataFlavor.class))).thenAnswer(inv -> {
             DataFlavor flavor = inv.getArgument(0);
             if (flavor.getMimeType().startsWith("text/uri-list")) {
                 return uriList;
             }
             return null;
        });
        
        boolean imported = handler.importData(support);
        
        assertTrue(imported, "Should be imported");
        assertEquals(2, chatView.getAttachedFiles().size());
        assertTrue(chatView.getAttachedFiles().contains(file1));
        assertTrue(chatView.getAttachedFiles().contains(file2));
    }


    @Test
    void testImportData_MalformedUriWithSpaces() throws Exception {
        // Create a real file with spaces
        File file = tempDir.resolve("my file.txt").toFile();
        file.createNewFile();
        
        // Mock dependencies
        GenAIService genAIService = mock(GenAIService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ThemeService themeService = mock(ThemeService.class);
        SlashCommandService slashCommandService = mock(SlashCommandService.class);
        ProjectCacheMetaService projectCacheMetaService = mock(ProjectCacheMetaService.class);

        ChatView chatView = new ChatView(genAIService, settingsService, themeService, slashCommandService, projectCacheMetaService);
        ChatView.FileTransferHandler handler = chatView.new FileTransferHandler(null);
        
        TransferHandler.TransferSupport support = mock(TransferHandler.TransferSupport.class);
        java.awt.datatransfer.Transferable transferable = mock(java.awt.datatransfer.Transferable.class);
        
        when(support.getTransferable()).thenReturn(transferable);
        when(support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)).thenReturn(false);
        
        when(support.isDataFlavorSupported(any(DataFlavor.class))).thenAnswer(inv -> {
            DataFlavor flavor = inv.getArgument(0);
            return flavor.getMimeType().startsWith("text/uri-list");
        });
        
        // Construct the malformed URI string (not encoded)
        String uriList = "file://" + file.getAbsolutePath(); 
        
        when(transferable.getTransferData(any(DataFlavor.class))).thenAnswer(inv -> {
            DataFlavor flavor = inv.getArgument(0);
            if (flavor.getMimeType().startsWith("text/uri-list")) {
                return uriList;
            }
            throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
        });
        
        boolean imported = handler.importData(support);
        
        assertTrue(imported, "Should report handled");
        assertFalse(chatView.getAttachedFiles().isEmpty(), "Should have attached file");
        assertEquals("my file.txt", chatView.getAttachedFiles().get(0).getName());
    }

    @Test
    void testImportData_RawPath() throws Exception {
        // Create a real file
        File file = tempDir.resolve("somefile.txt").toFile();
        file.createNewFile();
        
        // Mock dependencies
        GenAIService genAIService = mock(GenAIService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ThemeService themeService = mock(ThemeService.class);
        SlashCommandService slashCommandService = mock(SlashCommandService.class);
        ProjectCacheMetaService projectCacheMetaService = mock(ProjectCacheMetaService.class);

        ChatView chatView = new ChatView(genAIService, settingsService, themeService, slashCommandService, projectCacheMetaService);
        ChatView.FileTransferHandler handler = chatView.new FileTransferHandler(null);
        
        TransferHandler.TransferSupport support = mock(TransferHandler.TransferSupport.class);
        java.awt.datatransfer.Transferable transferable = mock(java.awt.datatransfer.Transferable.class);
        
        when(support.getTransferable()).thenReturn(transferable);
        when(support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)).thenReturn(false);
        
         when(support.isDataFlavorSupported(any(DataFlavor.class))).thenAnswer(inv -> {
            DataFlavor flavor = inv.getArgument(0);
            return flavor.getMimeType().startsWith("text/uri-list");
        });
        
        // Simulating a raw path
        String uriList = file.getAbsolutePath(); 
        
        when(transferable.getTransferData(any(DataFlavor.class))).thenAnswer(inv -> {
            DataFlavor flavor = inv.getArgument(0);
            if (flavor.getMimeType().startsWith("text/uri-list")) {
                return uriList;
            }
            throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
        });
        
        boolean imported = handler.importData(support);
        
        assertTrue(imported, "Should report handled");
        assertFalse(chatView.getAttachedFiles().isEmpty(), "Should have attached file");
        assertEquals("somefile.txt", chatView.getAttachedFiles().get(0).getName());
    }
}

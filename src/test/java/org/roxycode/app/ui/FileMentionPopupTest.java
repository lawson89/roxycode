package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Dimension;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.MemoryService.MemoryMetadata;
import org.roxycode.app.ui.FileMentionPopup.FileCandidate;
import org.roxycode.app.ui.FileMentionPopup.MemoryCandidate;
import org.roxycode.app.ui.FileMentionPopup.MentionItem;
import org.roxycode.app.ui.FileMentionPopup.MentionToken;

class FileMentionPopupTest {

    @Test
    void testExtractMentionTokenAndRejectInvalidCases() {
        assertEquals(new MentionToken(0, 1, "", '@'), FileMentionPopup.extractMentionToken("@", 1));
        assertEquals(new MentionToken(0, 6, "ChatP", '@'), FileMentionPopup.extractMentionToken("@ChatP", 6));
        assertEquals(new MentionToken(0, 1, "", '/'), FileMentionPopup.extractMentionToken("/", 1));
        assertEquals(new MentionToken(0, 6, "mem-p", '/'), FileMentionPopup.extractMentionToken("/mem-p", 6));

        String sentence = "Please inspect @src/main/ChatPanel.java for details";
        int caretAfterPath = "Please inspect @src/main/ChatPanel.java".length();
        assertEquals(new MentionToken(15, caretAfterPath, "src/main/ChatPanel.java", '@'),
                FileMentionPopup.extractMentionToken(sentence, caretAfterPath));

        String multiLine = "First line\n@Pom";
        assertEquals(new MentionToken(11, multiLine.length(), "Pom", '@'),
                FileMentionPopup.extractMentionToken(multiLine, multiLine.length()));

        assertNull(FileMentionPopup.extractMentionToken("user@example.com", 16));
        assertNull(FileMentionPopup.extractMentionToken("contact user@domain", 19));
        assertNull(FileMentionPopup.extractMentionToken("@@double", 8));
        assertNull(FileMentionPopup.extractMentionToken("@ChatPanel.java ", 16));
        assertNull(FileMentionPopup.extractMentionToken("@ space", 7));
        assertNull(FileMentionPopup.extractMentionToken("no mention here", 15));
        assertNull(FileMentionPopup.extractMentionToken("@Chat", 0));
        assertNull(FileMentionPopup.extractMentionToken(null, 1));

        JTextArea docArea = new JTextArea("Please inspect @src/main/ChatPanel.java");
        assertEquals(new MentionToken(15, docArea.getText().length(), "src/main/ChatPanel.java", '@'),
                FileMentionPopup.extractMentionTokenFromDocument(docArea.getDocument(), docArea.getText().length()));
        docArea.setText("plain typing without any trigger");
        assertNull(FileMentionPopup.extractMentionTokenFromDocument(docArea.getDocument(), docArea.getText().length()));
    }

    @Test
    void testFileCandidateNormalizationMultiTierRankingAndResultCap() {
        String winPath = ".\\src\\main\\java\\org\\roxycode\\ChatPanel.java";
        assertEquals("src/main/java/org/roxycode/chatpanel.java", FileCandidate.fromPath(winPath).pathLower());

        FileCandidate rootFile = FileCandidate.fromPath("/pom.xml");
        assertEquals("pom.xml", rootFile.relativePath());
        assertEquals("pom.xml", rootFile.fileName());
        assertEquals("", rootFile.parentDir());
        assertEquals("pom.xml", rootFile.fileNameLower());

        List<FileCandidate> index = List.of(FileCandidate.fromPath("docs/chat_notes/guide.md"),
                FileCandidate.fromPath("src/ui/MyChatView.java"), FileCandidate.fromPath("src/ui/WechatHelper.java"),
                FileCandidate.fromPath("src/ui/ChatPanelTest.java"), FileCandidate.fromPath("src/ui/ChatPanel.java"),
                FileCandidate.fromPath("src/ui/Unrelated.java"));

        assertEquals(
                List.of("src/ui/ChatPanel.java", "src/ui/ChatPanelTest.java", "src/ui/MyChatView.java",
                        "src/ui/WechatHelper.java", "docs/chat_notes/guide.md"),
                FileMentionPopup.rankCandidates(index, new MentionToken(0, 4, "chat", '@')).stream()
                        .filter(m -> m instanceof FileCandidate).map(m -> ((FileCandidate) m).relativePath()).toList());

        List<FileCandidate> manyFiles = new ArrayList<>();
        for (int i = 25; i >= 1; i--) {
            manyFiles.add(FileCandidate.fromPath(String.format("src/File%02d.java", i)));
        }
        List<MentionItem> emptyQueryRanked = FileMentionPopup.rankCandidates(manyFiles,
                new MentionToken(0, 0, "", '@'));
        assertEquals(FileMentionPopup.MAX_RESULTS, emptyQueryRanked.size());
        assertEquals("src/File01.java", ((FileCandidate) emptyQueryRanked.get(0)).relativePath());
        assertEquals("src/File15.java", ((FileCandidate) emptyQueryRanked.get(14)).relativePath());
        assertEquals(FileMentionPopup.MAX_RESULTS,
                FileMentionPopup.rankCandidates(manyFiles, new MentionToken(0, 4, "File", '@')).size());

        List<MentionItem> allCommands = FileMentionPopup.rankCandidates(null, new MentionToken(0, 0, "", '/'));
        assertEquals(11, allCommands.size());
        assertEquals("/reset", allCommands.get(0).replacement());
        assertEquals("/clear", allCommands.get(1).replacement());
        assertEquals("/help", allCommands.get(2).replacement());

        List<MentionItem> commands = FileMentionPopup.rankCandidates(null, new MentionToken(0, 1, "mem-p", '/'));
        assertEquals(4, commands.size());
        assertEquals("/mem-p-view ", commands.get(0).replacement());
        assertEquals("/mem-p-put ", commands.get(1).replacement());
        assertEquals("/mem-p-find ", commands.get(2).replacement());
        assertEquals("/mem-p-del ", commands.get(3).replacement());
    }

    @Test
    void testUpwardPopupLocationClampingAndCellRenderer() {
        JTextArea inputArea = new JTextArea(3, 40);
        inputArea.setSize(new Dimension(400, 70));
        JScrollPane scrollPane = new JScrollPane(inputArea);
        scrollPane.setSize(new Dimension(400, 70));

        FileMentionPopup popup = new FileMentionPopup(inputArea, scrollPane);
        popup.setBackgroundExecutor(Runnable::run);

        GrepService grepService = mock(GrepService.class);
        when(grepService.listFiles())
                .thenReturn(List.of("src/main/java/org/roxycode/app/ui/ChatPanel.java", "README.md", "LICENSE"));
        popup.setGrepService(grepService);
        verify(grepService, times(1)).listFiles();

        inputArea.setText("Check @Chat");
        inputArea.setCaretPosition(inputArea.getText().length());
        SwingTestUtils.flushEdt();

        assertTrue(popup.isPopupVisible());
        assertEquals(1, popup.listModel.size());
        verify(grepService, times(1)).listFiles();

        popup.invalidateCache();
        verify(grepService, times(1)).listFiles();

        inputArea.setText("/help");
        inputArea.setCaretPosition(inputArea.getText().length());
        SwingTestUtils.flushEdt();
        verify(grepService, times(1)).listFiles();

        inputArea.setText("Check @Chat");
        inputArea.setCaretPosition(inputArea.getText().length());
        SwingTestUtils.flushEdt();
        assertTrue(popup.isPopupVisible());
        assertEquals(1, popup.listModel.size());
        verify(grepService, times(2)).listFiles();

        Point loc = popup.computePopupLocation(6, 320, 120);
        assertTrue(loc.x >= 0 && loc.x + 320 <= 400);
        assertTrue(loc.y < 0);

        assertInstanceOf(JPanel.class, SwingTestUtils.renderListCell(popup.candidateList, 0, true));
        SwingTestUtils.withDarkTheme(popup.popupMenu, () -> {
            popup.updateTheme();
            assertInstanceOf(JPanel.class, SwingTestUtils.renderListCell(popup.candidateList, 0, false));
        });
        assertEquals(Codicons.FILE_CODE, FileMentionPopup.resolveFileIcon("chatpanel.java"));
        assertEquals(Codicons.FILE, FileMentionPopup.resolveFileIcon("license"));
    }

    @Test
    void testMemoryIdTokenExtractionRankingAndTwoStageAutocomplete() {
        assertEquals(new MentionToken(0, 12, "mem-g-view ", '/'),
                FileMentionPopup.extractMentionToken("/mem-g-view ", 12));
        assertEquals(new MentionToken(0, 15, "mem-g-view no-", '/'),
                FileMentionPopup.extractMentionToken("/mem-g-view no-", 15));
        assertEquals(new MentionToken(0, 12, "mem-p-view ", '/'),
                FileMentionPopup.extractMentionToken("/mem-p-view ", 12));
        assertEquals(new MentionToken(0, 11, "mem-p-del ", '/'),
                FileMentionPopup.extractMentionToken("/mem-p-del ", 11));
        assertEquals(new MentionToken(0, 14, "mem-g-del foo", '/'),
                FileMentionPopup.extractMentionToken("/mem-g-del foo", 14));

        assertNull(FileMentionPopup.extractMentionToken("/mem-g-view foo bar", 19));
        assertNull(FileMentionPopup.extractMentionToken("/mem-p-put ", 11));
        assertNull(FileMentionPopup.extractMentionToken("/mem-g-find ", 12));

        JTextArea docArea = new JTextArea("/mem-p-view arch");
        assertEquals(new MentionToken(0, docArea.getText().length(), "mem-p-view arch", '/'),
                FileMentionPopup.extractMentionTokenFromDocument(docArea.getDocument(), docArea.getText().length()));

        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.list("global")).thenReturn(List.of(
                new MemoryMetadata("global", "prefer-libs", "Use open source libraries for tern", List.of(), false, ""),
                new MemoryMetadata("global", "my-tern-rule", "Custom rule", List.of(), false, ""),
                new MemoryMetadata("global", "no-var", "Do not use local var", List.of(), false, ""),
                new MemoryMetadata("global", "tern-style", "Tern style guide", List.of(), false, "")));
        when(memoryService.list("project")).thenReturn(List.of(
                new MemoryMetadata("project", "swing-html", "Swing HTML 3.2 constraints", List.of("ui"), true, "")));

        List<MentionItem> globalRanked = FileMentionPopup.rankCandidates(null,
                new MentionToken(0, 16, "mem-g-view tern", '/'), memoryService);
        assertEquals(3, globalRanked.size());
        assertEquals("tern-style", globalRanked.get(0).label());
        assertEquals("my-tern-rule", globalRanked.get(1).label());
        assertEquals("prefer-libs", globalRanked.get(2).label());
        assertEquals("/mem-g-view tern-style", globalRanked.get(0).replacement());

        List<MentionItem> projectRanked = FileMentionPopup.rankCandidates(null,
                new MentionToken(0, 11, "mem-p-del ", '/'), memoryService);
        assertEquals(1, projectRanked.size());
        assertEquals("/mem-p-del swing-html", projectRanked.get(0).replacement());

        JTextArea inputArea = new JTextArea(3, 40);
        inputArea.setSize(new Dimension(400, 70));
        JScrollPane scrollPane = new JScrollPane(inputArea);
        scrollPane.setSize(new Dimension(400, 70));

        FileMentionPopup popup = new FileMentionPopup(inputArea, scrollPane);
        popup.setBackgroundExecutor(Runnable::run);
        popup.setMemoryService(memoryService);

        inputArea.setText("/mem-g-v");
        inputArea.setCaretPosition(inputArea.getText().length());
        SwingTestUtils.flushEdt();

        assertTrue(popup.isPopupVisible());
        assertEquals("/mem-g-view ", popup.candidateList.getSelectedValue().replacement());

        assertTrue(popup.acceptSelected());
        assertEquals("/mem-g-view ", inputArea.getText());
        SwingTestUtils.flushEdt();

        assertTrue(popup.isPopupVisible());
        assertEquals(4, popup.listModel.size());
        assertInstanceOf(MemoryCandidate.class, popup.listModel.getElementAt(0));

        popup.candidateList.setSelectedIndex(2);
        assertTrue(popup.acceptSelected());
        assertEquals("/mem-g-view no-var", inputArea.getText());
        assertFalse(popup.isPopupVisible());
    }
}

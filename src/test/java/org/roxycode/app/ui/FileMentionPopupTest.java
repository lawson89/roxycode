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
import org.roxycode.app.ui.FileMentionPopup.FileCandidate;
import org.roxycode.app.ui.FileMentionPopup.MentionToken;

class FileMentionPopupTest {

    @Test
    void testExtractMentionTokenAtStartAfterWhitespaceAndMidSentence() {
        assertEquals(new MentionToken(0, 1, ""), FileMentionPopup.extractMentionToken("@", 1));
        assertEquals(new MentionToken(0, 6, "ChatP"), FileMentionPopup.extractMentionToken("@ChatP", 6));

        String sentence = "Please inspect @src/main/ChatPanel.java for details";
        int caretAfterPath = "Please inspect @src/main/ChatPanel.java".length();
        assertEquals(new MentionToken(15, caretAfterPath, "src/main/ChatPanel.java"),
                FileMentionPopup.extractMentionToken(sentence, caretAfterPath));

        String multiLine = "First line\n@Pom";
        assertEquals(new MentionToken(11, multiLine.length(), "Pom"),
                FileMentionPopup.extractMentionToken(multiLine, multiLine.length()));
    }

    @Test
    void testExtractMentionTokenRejectsEmailsWhitespaceAndInvalidOffsets() {
        assertNull(FileMentionPopup.extractMentionToken("user@example.com", 16));
        assertNull(FileMentionPopup.extractMentionToken("contact user@domain", 19));
        assertNull(FileMentionPopup.extractMentionToken("@@double", 8));
        assertNull(FileMentionPopup.extractMentionToken("@ChatPanel.java ", 16));
        assertNull(FileMentionPopup.extractMentionToken("@ space", 7));
        assertNull(FileMentionPopup.extractMentionToken("no mention here", 15));
        assertNull(FileMentionPopup.extractMentionToken("@Chat", 0));
        assertNull(FileMentionPopup.extractMentionToken(null, 1));
    }

    @Test
    void testFileCandidateFromPathNormalizesSlashesAndExtractsParent() {
        String winPath = ".\\src\\main\\java\\org\\roxycode\\ChatPanel.java";
        assertEquals("src/main/java/org/roxycode/chatpanel.java", FileCandidate.fromPath(winPath).pathLower());

        FileCandidate rootFile = FileCandidate.fromPath("/pom.xml");
        assertEquals("pom.xml", rootFile.relativePath());
        assertEquals("pom.xml", rootFile.fileName());
        assertEquals("", rootFile.parentDir());
        assertEquals("pom.xml", rootFile.fileNameLower());
    }

    @Test
    void testMultiTierRankingOrderAndTieBreaking() {
        List<FileCandidate> index = List.of(FileCandidate.fromPath("docs/chat_notes/guide.md"),
                FileCandidate.fromPath("src/ui/MyChatView.java"), FileCandidate.fromPath("src/ui/WechatHelper.java"),
                FileCandidate.fromPath("src/ui/ChatPanelTest.java"), FileCandidate.fromPath("src/ui/ChatPanel.java"),
                FileCandidate.fromPath("src/ui/Unrelated.java"));

        assertEquals(
                List.of("src/ui/ChatPanel.java", "src/ui/ChatPanelTest.java", "src/ui/MyChatView.java",
                        "src/ui/WechatHelper.java", "docs/chat_notes/guide.md"),
                FileMentionPopup.rankCandidates(index, "chat").stream().map(FileCandidate::relativePath).toList());
    }

    @Test
    void testCamelCaseHumpAndWordBoundaryMatching() {
        List<FileCandidate> index = List.of(FileCandidate.fromPath("src/ui/ChatPanel.java"),
                FileCandidate.fromPath("src/ui/CodebaseCachePanel.java"),
                FileCandidate.fromPath("src/ui/CopyHelper.java"), FileCandidate.fromPath("src/cp/Other.java"));

        assertEquals(List.of("src/ui/ChatPanel.java", "src/ui/CodebaseCachePanel.java", "src/cp/Other.java"),
                FileMentionPopup.rankCandidates(index, "cp").stream().map(FileCandidate::relativePath).toList());
        assertEquals(List.of("src/ui/ChatPanel.java"),
                FileMentionPopup.rankCandidates(index, "ChPa").stream().map(FileCandidate::relativePath).toList());
    }

    @Test
    void testEmptyQueryReturnsFirst15SortedAndCapsResults() {
        List<FileCandidate> manyFiles = new ArrayList<>();
        for (int i = 25; i >= 1; i--) {
            manyFiles.add(FileCandidate.fromPath(String.format("src/File%02d.java", i)));
        }
        List<FileCandidate> emptyQueryRanked = FileMentionPopup.rankCandidates(manyFiles, "");
        assertEquals(FileMentionPopup.MAX_RESULTS, emptyQueryRanked.size());
        assertEquals("src/File01.java", emptyQueryRanked.get(0).relativePath());
        assertEquals("src/File15.java", emptyQueryRanked.get(14).relativePath());
        assertEquals(FileMentionPopup.MAX_RESULTS, FileMentionPopup.rankCandidates(manyFiles, "File").size());
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
        assertEquals(1, popup.getListModel().size());
        verify(grepService, times(1)).listFiles();

        Point loc = popup.computePopupLocation(6, 320, 120);
        assertTrue(loc.x >= 0 && loc.x + 320 <= 400);
        assertTrue(loc.y < 0);

        assertInstanceOf(JPanel.class, SwingTestUtils.renderListCell(popup.getCandidateList(), 0, true));
        SwingTestUtils.withDarkTheme(popup.getPopupMenu(), () -> {
            popup.updateTheme();
            assertInstanceOf(JPanel.class, SwingTestUtils.renderListCell(popup.getCandidateList(), 0, false));
        });
        assertEquals(Codicons.FILE_CODE, FileMentionPopup.resolveFileIcon("chatpanel.java"));
        assertEquals(Codicons.FILE, FileMentionPopup.resolveFileIcon("license"));
    }
}

package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.ui.FileMentionPopup.FileCandidate;
import org.roxycode.app.ui.FileMentionPopup.MentionToken;

class FileMentionPopupTest {

    @Test
    void testExtractMentionTokenAtStartAfterWhitespaceAndMidSentence() {
        MentionToken atStart = FileMentionPopup.extractMentionToken("@", 1);
        assertNotNull(atStart);
        assertEquals(0, atStart.startOffset());
        assertEquals(1, atStart.endOffset());
        assertEquals("", atStart.query());

        MentionToken withQuery = FileMentionPopup.extractMentionToken("@ChatP", 6);
        assertNotNull(withQuery);
        assertEquals(0, withQuery.startOffset());
        assertEquals(6, withQuery.endOffset());
        assertEquals("ChatP", withQuery.query());

        String sentence = "Please inspect @src/main/ChatPanel.java for details";
        int caretAfterPath = "Please inspect @src/main/ChatPanel.java".length();
        MentionToken midSentence = FileMentionPopup.extractMentionToken(sentence, caretAfterPath);
        assertNotNull(midSentence);
        assertEquals(15, midSentence.startOffset());
        assertEquals(caretAfterPath, midSentence.endOffset());
        String multiLine = "First line\n@Pom";
        MentionToken afterNewline =
                FileMentionPopup.extractMentionToken(multiLine, multiLine.length());
        assertNotNull(afterNewline);
        assertEquals(11, afterNewline.startOffset());
        assertEquals("Pom", afterNewline.query());
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
        char bs = (char) 92;
        String winPath =
                "."
                        + bs
                        + "src"
                        + bs
                        + "main"
                        + bs
                        + "java"
                        + bs
                        + "org"
                        + bs
                        + "roxycode"
                        + bs
                        + "ChatPanel.java";
        FileCandidate nested = FileCandidate.fromPath(winPath);
        assertEquals("src/main/java/org/roxycode/chatpanel.java", nested.pathLower());

        FileCandidate rootFile = FileCandidate.fromPath("/pom.xml");
        assertEquals("pom.xml", rootFile.relativePath());
        assertEquals("pom.xml", rootFile.fileName());
        assertEquals("", rootFile.parentDir());
        assertEquals("pom.xml", rootFile.fileNameLower());
    }

    @Test
    void testMultiTierRankingOrderAndTieBreaking() {
        List<FileCandidate> index =
                List.of(
                        FileCandidate.fromPath(
                                "docs/chat_notes/guide.md"), // Tier 4: path match "chat"
                        FileCandidate.fromPath(
                                "src/ui/MyChatView.java"), // Tier 2: word boundary "Chat"
                        FileCandidate.fromPath(
                                "src/ui/WechatHelper.java"), // Tier 3: filename substring "chat"
                        FileCandidate.fromPath(
                                "src/ui/ChatPanelTest.java"), // Tier 1: prefix "chat" (longer)
                        FileCandidate.fromPath(
                                "src/ui/ChatPanel.java"), // Tier 1: prefix "chat" (shorter)
                        FileCandidate.fromPath("src/ui/Unrelated.java"));

        List<FileCandidate> ranked = FileMentionPopup.rankCandidates(index, "chat");
        assertEquals(5, ranked.size());
        assertEquals("src/ui/ChatPanel.java", ranked.get(0).relativePath());
        assertEquals("src/ui/ChatPanelTest.java", ranked.get(1).relativePath());
        assertEquals("src/ui/MyChatView.java", ranked.get(2).relativePath());
        assertEquals("src/ui/WechatHelper.java", ranked.get(3).relativePath());
        assertEquals("docs/chat_notes/guide.md", ranked.get(4).relativePath());
    }

    @Test
    void testCamelCaseHumpAndWordBoundaryMatching() {
        List<FileCandidate> index =
                List.of(
                        FileCandidate.fromPath("src/ui/ChatPanel.java"),
                        FileCandidate.fromPath("src/ui/CodebaseCachePanel.java"),
                        FileCandidate.fromPath("src/ui/CopyHelper.java"),
                        FileCandidate.fromPath("src/cp/Other.java"));

        List<FileCandidate> rankedCp = FileMentionPopup.rankCandidates(index, "cp");
        assertEquals(3, rankedCp.size());
        // ChatPanel.java (C + P) and CodebaseCachePanel.java (C + P) are Tier 2; src/cp/Other.java
        // is Tier 4
        assertEquals("src/ui/ChatPanel.java", rankedCp.get(0).relativePath());
        assertEquals("src/ui/CodebaseCachePanel.java", rankedCp.get(1).relativePath());
        assertEquals("src/cp/Other.java", rankedCp.get(2).relativePath());

        List<FileCandidate> rankedChPa = FileMentionPopup.rankCandidates(index, "ChPa");
        assertEquals(1, rankedChPa.size());
        assertEquals("src/ui/ChatPanel.java", rankedChPa.get(0).relativePath());
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

        List<FileCandidate> filteredRanked = FileMentionPopup.rankCandidates(manyFiles, "File");
        assertEquals(FileMentionPopup.MAX_RESULTS, filteredRanked.size());
    }

    @Test
    void testUpwardPopupLocationClampingAndCellRenderer() throws Exception {
        JTextArea inputArea = new JTextArea(3, 40);
        inputArea.setSize(new Dimension(400, 70));
        JScrollPane scrollPane = new JScrollPane(inputArea);
        scrollPane.setSize(new Dimension(400, 70));

        FileMentionPopup popup = new FileMentionPopup(inputArea, scrollPane);
        popup.setBackgroundExecutor(Runnable::run);

        GrepService grepService = mock(GrepService.class);
        when(grepService.listFiles())
                .thenReturn(
                        List.of(
                                "src/main/java/org/roxycode/app/ui/ChatPanel.java",
                                "README.md",
                                "LICENSE"));
        popup.setGrepService(grepService);

        // Verify zero extra process spawns when typing multiple characters with warm cache
        verify(grepService, times(1)).listFiles();

        inputArea.setText("Check @Chat");
        inputArea.setCaretPosition(inputArea.getText().length());
        javax.swing.SwingUtilities.invokeAndWait(() -> {});

        assertTrue(popup.isPopupVisible());
        assertEquals(1, popup.getListModel().size());
        verify(grepService, times(1)).listFiles();

        Point loc = popup.computePopupLocation(6, 320, 120);
        assertTrue(loc.x >= 0 && loc.x + 320 <= 400);
        assertTrue(loc.y < 0, "Popup Y coordinate must be positioned above the caret line");

        ListCellRenderer<? super FileCandidate> renderer =
                popup.getCandidateList().getCellRenderer();
        Component comp =
                renderer.getListCellRendererComponent(
                        popup.getCandidateList(), popup.getListModel().get(0), 0, true, false);
        assertInstanceOf(JPanel.class, comp);
        assertEquals(Codicons.FILE_CODE, FileMentionPopup.resolveFileIcon("chatpanel.java"));
        assertEquals(Codicons.FILE, FileMentionPopup.resolveFileIcon("license"));
    }
}

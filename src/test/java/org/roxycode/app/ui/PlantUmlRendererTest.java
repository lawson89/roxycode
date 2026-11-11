package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsNone;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlantUmlRendererTest {

    private static final Pattern IMG_SRC_PATTERN = Pattern.compile("src=.(file:[^ ]+). width=");

    @BeforeEach
    void setUp() {
        PlantUmlRenderer.clearCacheForTesting();
    }

    @Test
    void testIsDiagramLanguageAndSourceNormalization() {
        assertTrue(PlantUmlRenderer.isDiagramLanguage("plantuml"));
        assertTrue(PlantUmlRenderer.isDiagramLanguage("  PLANTUML  "));
        assertTrue(PlantUmlRenderer.isDiagramLanguage("puml"));
        assertTrue(PlantUmlRenderer.isDiagramLanguage("uml"));
        assertTrue(PlantUmlRenderer.isDiagramLanguage("ditaa"));
        assertTrue(PlantUmlRenderer.isDiagramLanguage("plantuml title=demo"));
        assertFalse(PlantUmlRenderer.isDiagramLanguage("java"));
        assertFalse(PlantUmlRenderer.isDiagramLanguage(""));
        assertFalse(PlantUmlRenderer.isDiagramLanguage(null));

        assertTrue(PlantUmlRenderer.isDiagramBlock("uml", "Alice -> Bob"));
        assertTrue(PlantUmlRenderer.isDiagramBlock("", "@startuml\nAlice -> Bob\n@enduml"));
        assertTrue(PlantUmlRenderer.isDiagramBlock(null, "  @STARTDITAA\n+---+\n@endditaa"));
        assertTrue(PlantUmlRenderer.isDiagramBlock("", "@startmindmap\n* Root\n@endmindmap"));
        assertTrue(PlantUmlRenderer.isDiagramBlock("", "@startwbs\n* Root\n@endwbs"));
        assertFalse(PlantUmlRenderer.isDiagramBlock("", "public class Demo {}"));
        assertFalse(PlantUmlRenderer.isDiagramBlock(null, null));

        String lightUml = PlantUmlRenderer.buildPlantUmlSource("Alice -> Bob : Hello", "plantuml", false);
        assertTrue(lightUml.startsWith("@startuml\n"));
        assertTrue(lightUml.endsWith("\n@enduml"));
        assertContainsAll(lightUml, "!pragma layout smetana", "skinparam backgroundColor #FFFFFF",
                "skinparam ParticipantBackgroundColor #F8FAFC", "skinparam CardBackgroundColor #F8FAFC",
                "skinparam CardBorderColor #334155", "skinparam CardFontColor #0F172A", "Alice -> Bob : Hello");

        String darkUml = PlantUmlRenderer.buildPlantUmlSource("@startuml\nAlice -> Bob\n@enduml", "puml", true);
        assertContainsAll(darkUml, "!pragma layout smetana", "skinparam backgroundColor #252526",
                "skinparam ParticipantBackgroundColor #2D2D30", "skinparam CardBackgroundColor #2D2D30",
                "skinparam CardBorderColor #94A3B8", "skinparam CardFontColor #E2E8F0", "Alice -> Bob");

        String mindmap = PlantUmlRenderer.buildPlantUmlSource("@startmindmap\n* Root\n** Child\n@endmindmap",
                "plantuml", true);
        assertTrue(mindmap.startsWith("@startmindmap\n"));
        assertContainsAll(mindmap, "<style>", "mindmapDiagram", "#252526", "* Root");
        assertContainsNone(mindmap, "@startuml", "!pragma layout smetana");

        String ditaa = PlantUmlRenderer.buildPlantUmlSource("+------+\n| Box  |\n+------+", "ditaa", false);
        assertEquals("@startditaa\n+------+\n| Box  |\n+------+\n@endditaa", ditaa);
        assertContainsNone(ditaa, "skinparam");
    }

    @Test
    void testRenderSequenceAndActivityDiagramsInLightAndDarkModesAndCacheReuse() throws Exception {
        String sequenceCode = """
                Alice -> Bob: Authentication Request
                Bob --> Alice: Authentication Response
                """;
        Optional<String> lightHtml = PlantUmlRenderer.renderDiagramHtml(sequenceCode, "plantuml", false);
        assertTrue(lightHtml.isPresent());
        assertContainsAll(lightHtml.get(), "diagram-container", "<img src=", "file:", "width=", "height=");
        Path lightPng = extractImagePath(lightHtml.get());
        assertTrue(Files.isRegularFile(lightPng));
        assertTrue(Files.size(lightPng) > 0);

        int countAfterFirst = PlantUmlRenderer.getRenderCountForTesting();
        assertEquals(1, countAfterFirst);

        Optional<String> cachedLightHtml = PlantUmlRenderer.renderDiagramHtml(sequenceCode, "plantuml", false);
        assertEquals(lightHtml, cachedLightHtml);
        assertEquals(1, PlantUmlRenderer.getRenderCountForTesting());

        Optional<String> darkHtml = PlantUmlRenderer.renderDiagramHtml(sequenceCode, "plantuml", true);
        assertTrue(darkHtml.isPresent());
        assertNotEquals(lightHtml.get(), darkHtml.get());
        assertEquals(2, PlantUmlRenderer.getRenderCountForTesting());
        Path darkPng = extractImagePath(darkHtml.get());
        assertTrue(Files.isRegularFile(darkPng));
        assertTrue(Files.size(darkPng) > 0);

        String activityCode = """
                start
                :Initialize service;
                if (Valid?) then (yes)
                  :Execute request;
                else (no)
                  :Reject request;
                endif
                stop
                """;
        Optional<String> activityHtml = PlantUmlRenderer.renderDiagramHtml(activityCode, "puml", false);
        assertTrue(activityHtml.isPresent());
        Path activityPng = extractImagePath(activityHtml.get());
        assertTrue(Files.isRegularFile(activityPng));
        assertTrue(Files.size(activityPng) > 0);
    }

    @Test
    void testRenderDitaaDiagramAndInvalidSyntaxFallback() throws Exception {
        String ditaaCode = """
                +--------+   +--------+
                | Client |-->| Server |
                +--------+   +--------+
                """;
        Optional<String> ditaaLight = PlantUmlRenderer.renderDiagramHtml(ditaaCode, "ditaa", false);
        assertTrue(ditaaLight.isPresent());
        Path ditaaPng = extractImagePath(ditaaLight.get());
        assertTrue(Files.isRegularFile(ditaaPng));
        assertTrue(Files.size(ditaaPng) > 0);

        Optional<String> ditaaDark = PlantUmlRenderer.renderDiagramHtml(ditaaCode, "ditaa", true);
        assertTrue(ditaaDark.isPresent());

        assertTrue(PlantUmlRenderer.renderDiagramHtml("", "plantuml", false).isEmpty());
        assertTrue(PlantUmlRenderer.renderDiagramHtml(null, "plantuml", false).isEmpty());
        assertTrue(PlantUmlRenderer.renderDiagramHtml("Alice -> Bob", "java", false).isEmpty());
        assertTrue(
                PlantUmlRenderer.renderDiagramHtml("not valid plantuml syntax @@@ ???", "plantuml", false).isEmpty());
    }

    @Test
    void testSanitizeMultilineQuotedStringsAndRenderNestedRectangleCardDiagram() throws Exception {
        String raw = """
                @startuml
                top to bottom direction
                skinparam shadowing false

                rectangle "Step 1: Estimate Per-Message Tokens
                (TokenUtils.estimateTokens)" as Step1 {
                  card "Msg 0: 1.5k tok" as M0
                  card "Msg 1: 2.0k tok" as M1
                }

                rectangle "Step 2: Walk Backward from Newest -> Oldest
                Accumulating Protected Recent Tail (<= 16,000 tokens)" as Step2 {
                  card "Protected Tail: Msg 5 + Msg 4 + Msg 3
                  Total = 6,700 tokens (Locked Prefix Boundary = Index 3)" as Tail
                }

                Step1 -down-> Step2 : Raw ToolResponseMessages
                @enduml
                """;
        String sanitized = PlantUmlRenderer.sanitizeMultilineQuotedStrings(raw);
        assertContainsAll(sanitized,
                "rectangle " + '"' + "Step 1: Estimate Per-Message Tokens" + "\\n(TokenUtils.estimateTokens)" + '"'
                        + " as Step1 {",
                "rectangle " + '"' + "Step 2: Walk Backward from Newest -> Oldest"
                        + "\\nAccumulating Protected Recent Tail (<= 16,000 tokens)" + '"' + " as Step2 {",
                "card " + '"' + "Protected Tail: Msg 5 + Msg 4 + Msg 3"
                        + "\\nTotal = 6,700 tokens (Locked Prefix Boundary = Index 3)" + '"' + " as Tail");

        // Verify escaped quotes and stray unclosed quotes do not corrupt subsequent lines
        String escQuote = "\\" + '"';
        String nlEscape = "\\n";
        String withEscapedQuote = "rectangle " + '"' + "Line 1 " + escQuote + "quoted" + escQuote + "\n   Line 2" + '"'
                + " as R1\nR1 -> R2";
        assertEquals("rectangle " + '"' + "Line 1 " + escQuote + "quoted" + escQuote + nlEscape + "Line 2" + '"'
                + " as R1\nR1 -> R2", PlantUmlRenderer.sanitizeMultilineQuotedStrings(withEscapedQuote));
        String strayUnclosedQuote = "note left: unmatched " + '"' + "quote\nAlice -> Bob";
        assertEquals(strayUnclosedQuote, PlantUmlRenderer.sanitizeMultilineQuotedStrings(strayUnclosedQuote));
        // Render via unlabeled @startuml block and 'uml' fence tag
        Optional<String> renderedUnlabeled = PlantUmlRenderer.renderDiagramHtml(raw, "", false);
        assertTrue(renderedUnlabeled.isPresent());
        Path pngPath = extractImagePath(renderedUnlabeled.get());
        assertTrue(Files.isRegularFile(pngPath));
        assertTrue(Files.size(pngPath) > 0);

        Optional<String> renderedUmlTag = PlantUmlRenderer.renderDiagramHtml(raw, "uml", true);
        assertTrue(renderedUmlTag.isPresent());
    }

    private static Path extractImagePath(String html) throws Exception {
        Matcher matcher = IMG_SRC_PATTERN.matcher(html);
        assertTrue(matcher.find(), "Expected <img src='file:...'> in HTML: " + html);
        return Path.of(new URI(matcher.group(1)));
    }
}

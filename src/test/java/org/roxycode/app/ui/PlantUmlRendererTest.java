package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsNone;

import com.github.weisj.jsvg.geometry.size.FloatSize;
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
        assertFalse(PlantUmlRenderer.isDiagramLanguage("salt"));
        assertTrue(PlantUmlRenderer.isDiagramLanguage("svg"));
        assertTrue(PlantUmlRenderer.isDiagramLanguage("plantuml title=demo"));
        assertFalse(PlantUmlRenderer.isDiagramLanguage("java"));
        assertFalse(PlantUmlRenderer.isDiagramLanguage(""));
        assertFalse(PlantUmlRenderer.isDiagramLanguage(null));

        assertTrue(PlantUmlRenderer.isDiagramBlock("uml", "Alice -> Bob"));
        assertTrue(PlantUmlRenderer.isDiagramBlock("", "@startuml\nAlice -> Bob\n@enduml"));
        assertTrue(PlantUmlRenderer.isDiagramBlock(null, "  @STARTDITAA\n+---+\n@endditaa"));
        assertTrue(PlantUmlRenderer.isDiagramBlock("", "@startmindmap\n* Root\n@endmindmap"));
        assertTrue(PlantUmlRenderer.isDiagramBlock("", "@startwbs\n* Root\n@endwbs"));
        assertTrue(PlantUmlRenderer.isDiagramBlock("svg", "<svg width='100' height='40'></svg>"));
        assertTrue(PlantUmlRenderer.isDiagramBlock("",
                "<svg width='100' height='40'><rect width='10' height='10'/></svg>"));
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

    @Test
    void testRenderInlineSvgWithDimensionClampingAndFallback() throws Exception {
        String svgContent = """
                <svg xmlns="http://www.w3.org/2000/svg" width="240" height="80" viewBox="0 0 240 80">
                  <rect x="4" y="4" width="232" height="72" rx="8" fill="#1E293B" stroke="#38BDF8" stroke-width="2"/>
                  <text x="20" y="46" fill="#F8FAFC" font-size="16">RoxyCode SVG</text>
                </svg>
                """;
        Optional<String> svgHtml = PlantUmlRenderer.renderDiagramHtml(svgContent, "svg", false);
        assertTrue(svgHtml.isPresent());
        assertContainsAll(svgHtml.get(), "diagram-container", "<img src=", "width=" + '"' + "240" + '"',
                "height=" + '"' + "80" + '"');
        Path svgPng = extractImagePath(svgHtml.get());
        assertTrue(Files.isRegularFile(svgPng));
        assertTrue(Files.size(svgPng) > 0);

        Optional<String> unlabeledSvgHtml = PlantUmlRenderer.renderDiagramHtml(svgContent, "", true);
        assertTrue(unlabeledSvgHtml.isPresent());

        assertArrayEquals(new int[]{600, 400}, PlantUmlRenderer.resolveSvgRenderDimensions(null));
        assertArrayEquals(new int[]{600, 400}, PlantUmlRenderer.resolveSvgRenderDimensions(new FloatSize(0, 0)));
        assertArrayEquals(new int[]{320, 180}, PlantUmlRenderer.resolveSvgRenderDimensions(new FloatSize(320, 180)));
        assertArrayEquals(new int[]{1600, 800}, PlantUmlRenderer.resolveSvgRenderDimensions(new FloatSize(3200, 1600)));

        assertTrue(PlantUmlRenderer.renderDiagramHtml("<svg><unclosed></svg>", "svg", false).isEmpty());
        assertTrue(PlantUmlRenderer.renderDiagramHtml("not valid svg", "svg", false).isEmpty());
    }

    @Test
    void testResolveIntrinsicSvgSizeAndPercentageNormalization() throws Exception {
        String mixedPercentSvg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1040 690" width="100%" height="690">
                  <rect x="0" y="0" width="1040" height="690" fill="#1E293B"/>
                </svg>
                """;
        FloatSize mixedSize = PlantUmlRenderer.resolveIntrinsicSvgSize(mixedPercentSvg, new FloatSize(100, 690));
        assertEquals(1040.0f, mixedSize.width, 0.01f);
        assertEquals(690.0f, mixedSize.height, 0.01f);
        Optional<String> mixedHtml = PlantUmlRenderer.renderDiagramHtml(mixedPercentSvg, "svg", false);
        assertTrue(mixedHtml.isPresent());
        assertContainsAll(mixedHtml.get(), "width=" + '"' + "1040" + '"', "height=" + '"' + "690" + '"');

        String bothPercentSvg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1040 690" width="100%" height="100%">
                  <rect x="0" y="0" width="1040" height="690" fill="#1E293B"/>
                </svg>
                """;
        Optional<String> bothPercentHtml = PlantUmlRenderer.renderDiagramHtml(bothPercentSvg, "svg", false);
        assertTrue(bothPercentHtml.isPresent());
        assertContainsAll(bothPercentHtml.get(), "width=" + '"' + "1040" + '"', "height=" + '"' + "690" + '"');

        String viewBoxOnlySvg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1040 690">
                  <rect x="0" y="0" width="1040" height="690" fill="#1E293B"/>
                </svg>
                """;
        Optional<String> viewBoxOnlyHtml = PlantUmlRenderer.renderDiagramHtml(viewBoxOnlySvg, "svg", false);
        assertTrue(viewBoxOnlyHtml.isPresent());
        assertContainsAll(viewBoxOnlyHtml.get(), "width=" + '"' + "1040" + '"', "height=" + '"' + "690" + '"');

        String noViewBoxPercentSvg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="100%" height="100%">
                  <rect x="0" y="0" width="600" height="400" fill="#1E293B"/>
                </svg>
                """;
        Optional<String> noViewBoxHtml = PlantUmlRenderer.renderDiagramHtml(noViewBoxPercentSvg, "svg", false);
        assertTrue(noViewBoxHtml.isPresent());
        assertContainsAll(noViewBoxHtml.get(), "width=" + '"' + "600" + '"', "height=" + '"' + "400" + '"');

        String commaViewBoxSvg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0, 0, 800, 450" width="100%" height="100%">
                  <rect x="0" y="0" width="800" height="450" fill="#1E293B"/>
                </svg>
                """;
        Optional<String> commaViewBoxHtml = PlantUmlRenderer.renderDiagramHtml(commaViewBoxSvg, "svg", false);
        assertTrue(commaViewBoxHtml.isPresent());
        assertContainsAll(commaViewBoxHtml.get(), "width=" + '"' + "800" + '"', "height=" + '"' + "450" + '"');

        String hyphenatedAttrsSvg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1040 690" stroke-width="2" line-height="1.5" width="100%" height="690">
                  <rect x="0" y="0" width="1040" height="690" fill="#1E293B"/>
                </svg>
                """;
        String normalizedHyphenated = PlantUmlRenderer.normalizeRootSvgAttributes(hyphenatedAttrsSvg);
        assertContainsAll(normalizedHyphenated, "stroke-width=" + '"' + "2" + '"', "line-height=" + '"' + "1.5" + '"',
                "width=" + '"' + "1040" + '"', "height=" + '"' + "690" + '"');
        FloatSize hyphenatedSize = PlantUmlRenderer.resolveIntrinsicSvgSize(normalizedHyphenated,
                new FloatSize(1040, 690));
        assertEquals(1040.0f, hyphenatedSize.width, 0.01f);
        assertEquals(690.0f, hyphenatedSize.height, 0.01f);
    }

    private static Path extractImagePath(String html) throws Exception {
        Matcher matcher = IMG_SRC_PATTERN.matcher(html);
        assertTrue(matcher.find(), "Expected <img src='file:...'> in HTML: " + html);
        return Path.of(new URI(matcher.group(1)));
    }
}

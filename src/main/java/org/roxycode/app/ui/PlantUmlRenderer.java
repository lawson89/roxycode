package org.roxycode.app.ui;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.core.DiagramDescription;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PlantUmlRenderer {

    private static final Logger log = LoggerFactory.getLogger(PlantUmlRenderer.class);

    static final int MAX_CACHE_ENTRIES = 200;
    private static final String TEMP_DIR_PREFIX = "roxycode-diagrams-";
    private static final String LANG_PLANTUML = "plantuml";
    private static final String LANG_PUML = "puml";
    private static final String LANG_UML = "uml";
    private static final String LANG_DITAA = "ditaa";
    private static final Set<String> DIAGRAM_LANGUAGES = Set.of(LANG_PLANTUML, LANG_PUML, LANG_UML, LANG_DITAA);

    private static final String START_PREFIX = "@start";
    private static final String START_UML = "@startuml";
    private static final String END_UML = "@enduml";
    private static final String START_DITAA = "@startditaa";
    private static final String END_DITAA = "@endditaa";
    private static final String START_MINDMAP = "@startmindmap";
    private static final String START_WBS = "@startwbs";
    private static final String SMETANA_PRAGMA = "!pragma layout smetana";
    private static final String ERROR_DESCRIPTION_MARKER = "(Error)";
    private static final char DOUBLE_QUOTE_CHAR = '"';
    private static final char BACKSLASH_CHAR = '\\';
    private static final char NEWLINE_CHAR = '\n';

    private static final int DITAA_FONT_SIZE = 13;
    private static final int DITAA_PADDING_X = 16;
    private static final int DITAA_PADDING_Y = 12;

    private static final String LIGHT_THEME_DIRECTIVES = """
            skinparam backgroundColor #FFFFFF
            skinparam shadowing false
            skinparam defaultFontName sans-serif
            skinparam defaultFontSize 12
            skinparam defaultFontColor #0F172A
            skinparam ArrowColor #334155
            skinparam BorderColor #334155
            skinparam RectangleBackgroundColor #F8FAFC
            skinparam RectangleBorderColor #334155
            skinparam ComponentBackgroundColor #F8FAFC
            skinparam ComponentBorderColor #334155
            skinparam ClassBackgroundColor #F8FAFC
            skinparam ClassBorderColor #334155
            skinparam StateBackgroundColor #F8FAFC
            skinparam StateBorderColor #334155
            skinparam ActivityBackgroundColor #F8FAFC
            skinparam ActivityBorderColor #334155
            skinparam ActivityDiamondBackgroundColor #F8FAFC
            skinparam ActivityDiamondBorderColor #334155
            skinparam ParticipantBackgroundColor #F8FAFC
            skinparam ParticipantBorderColor #334155
            skinparam ParticipantFontColor #0F172A
            skinparam SequenceLifeLineBorderColor #334155
            skinparam SequenceGroupBackgroundColor #F8FAFC
            skinparam SequenceGroupBorderColor #334155
            skinparam SequenceBoxBackgroundColor #F8FAFC
            skinparam SequenceBoxBorderColor #334155
            skinparam NoteBackgroundColor #F8FAFC
            skinparam NoteBorderColor #334155
            skinparam NoteFontColor #0F172A
            skinparam PackageBackgroundColor #FFFFFF
            skinparam PackageBorderColor #334155
            skinparam DatabaseBackgroundColor #F8FAFC
            skinparam DatabaseBorderColor #334155
            skinparam NodeBackgroundColor #F8FAFC
            skinparam NodeBorderColor #334155
            skinparam CardBackgroundColor #F8FAFC
            skinparam CardBorderColor #334155
            skinparam CardFontColor #0F172A
            """;

    private static final String DARK_THEME_DIRECTIVES = """
            skinparam backgroundColor #252526
            skinparam shadowing false
            skinparam defaultFontName sans-serif
            skinparam defaultFontSize 12
            skinparam defaultFontColor #E2E8F0
            skinparam ArrowColor #94A3B8
            skinparam BorderColor #94A3B8
            skinparam RectangleBackgroundColor #2D2D30
            skinparam RectangleBorderColor #94A3B8
            skinparam ComponentBackgroundColor #2D2D30
            skinparam ComponentBorderColor #94A3B8
            skinparam ClassBackgroundColor #2D2D30
            skinparam ClassBorderColor #94A3B8
            skinparam StateBackgroundColor #2D2D30
            skinparam StateBorderColor #94A3B8
            skinparam ActivityBackgroundColor #2D2D30
            skinparam ActivityBorderColor #94A3B8
            skinparam ActivityDiamondBackgroundColor #2D2D30
            skinparam ActivityDiamondBorderColor #94A3B8
            skinparam ParticipantBackgroundColor #2D2D30
            skinparam ParticipantBorderColor #94A3B8
            skinparam ParticipantFontColor #E2E8F0
            skinparam SequenceLifeLineBorderColor #94A3B8
            skinparam SequenceGroupBackgroundColor #1E1E1E
            skinparam SequenceGroupBorderColor #94A3B8
            skinparam SequenceBoxBackgroundColor #1E1E1E
            skinparam SequenceBoxBorderColor #94A3B8
            skinparam NoteBackgroundColor #1E1E1E
            skinparam NoteBorderColor #94A3B8
            skinparam NoteFontColor #E2E8F0
            skinparam PackageBackgroundColor #1E1E1E
            skinparam PackageBorderColor #94A3B8
            skinparam DatabaseBackgroundColor #2D2D30
            skinparam DatabaseBorderColor #94A3B8
            skinparam NodeBackgroundColor #2D2D30
            skinparam NodeBorderColor #94A3B8
            skinparam CardBackgroundColor #2D2D30
            skinparam CardBorderColor #94A3B8
            skinparam CardFontColor #E2E8F0
            """;

    private static final String LIGHT_MINDMAP_STYLE = """
            <style>
            mindmapDiagram {
              BackGroundColor #FFFFFF
              node {
                BackgroundColor #F8FAFC
                LineColor #334155
                FontColor #0F172A
              }
              arrow {
                LineColor #334155
              }
            }
            wbsDiagram {
              BackGroundColor #FFFFFF
              node {
                BackgroundColor #F8FAFC
                LineColor #334155
                FontColor #0F172A
              }
              arrow {
                LineColor #334155
              }
            }
            </style>
            """;

    private static final String DARK_MINDMAP_STYLE = """
            <style>
            mindmapDiagram {
              BackGroundColor #252526
              node {
                BackgroundColor #2D2D30
                LineColor #94A3B8
                FontColor #E2E8F0
              }
              arrow {
                LineColor #94A3B8
              }
            }
            wbsDiagram {
              BackGroundColor #252526
              node {
                BackgroundColor #2D2D30
                LineColor #94A3B8
                FontColor #E2E8F0
              }
              arrow {
                LineColor #94A3B8
              }
            }
            </style>
            """;

    public record DiagramCacheKey(String normalizedCode, boolean dark, String language) {
    }

    private static final Cache<DiagramCacheKey, Optional<String>> DIAGRAM_CACHE = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_ENTRIES).build();

    private static final AtomicInteger RENDER_COUNT = new AtomicInteger(0);
    private static volatile Path tempDiagramDir;

    private PlantUmlRenderer() {
    }

    public static boolean isDiagramLanguage(String language) {
        String normalized = normalizeLanguage(language);
        return DIAGRAM_LANGUAGES.contains(normalized);
    }

    public static boolean isDiagramBlock(String language, String code) {
        if (isDiagramLanguage(language)) {
            return true;
        }
        return startsWithDiagramDirective(code);
    }

    private static boolean startsWithDiagramDirective(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String lower = code.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith(START_UML) || lower.startsWith(START_DITAA) || lower.startsWith(START_MINDMAP)
                || lower.startsWith(START_WBS);
    }

    static String resolveEffectiveLanguage(String language, String code) {
        String normalized = normalizeLanguage(language);
        if (LANG_DITAA.equals(normalized)) {
            return LANG_DITAA;
        }
        if (code != null && code.trim().toLowerCase(Locale.ROOT).startsWith(START_DITAA)) {
            return LANG_DITAA;
        }
        return LANG_PLANTUML;
    }

    static String normalizeLanguage(String language) {
        if (language == null) {
            return "";
        }
        String trimmed = language.trim().toLowerCase(Locale.ROOT);
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx > 0) {
            return trimmed.substring(0, spaceIdx);
        }
        return trimmed;
    }

    public static Optional<String> renderDiagramHtml(String code, String language, boolean darkTheme) {
        if (code == null || code.isBlank() || !isDiagramBlock(language, code)) {
            return Optional.empty();
        }
        String effectiveLanguage = resolveEffectiveLanguage(language, code);
        String normalizedSource = buildPlantUmlSource(code, effectiveLanguage, darkTheme);
        if (normalizedSource.isBlank()) {
            return Optional.empty();
        }
        DiagramCacheKey cacheKey = new DiagramCacheKey(normalizedSource, darkTheme, effectiveLanguage);
        return DIAGRAM_CACHE.get(cacheKey, PlantUmlRenderer::generateDiagramHtml);
    }

    static String sanitizeMultilineQuotedStrings(String umlBody) {
        if (umlBody == null || umlBody.indexOf(DOUBLE_QUOTE_CHAR) < 0 || umlBody.indexOf(NEWLINE_CHAR) < 0) {
            return umlBody;
        }
        StringBuilder sb = new StringBuilder(umlBody.length() + 16);
        boolean inQuotes = false;
        int len = umlBody.length();
        int i = 0;
        while (i < len) {
            char c = umlBody.charAt(i);
            if (c == BACKSLASH_CHAR && i + 1 < len) {
                sb.append(c).append(umlBody.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == DOUBLE_QUOTE_CHAR) {
                if (inQuotes) {
                    inQuotes = false;
                } else if (hasClosingQuote(umlBody, i + 1)) {
                    inQuotes = true;
                }
                sb.append(c);
                i++;
                continue;
            }
            if (c == NEWLINE_CHAR && inQuotes) {
                sb.append("\\n");
                i++;
                while (i < len) {
                    char nextChar = umlBody.charAt(i);
                    if (nextChar == ' ' || nextChar == '\t') {
                        i++;
                    } else {
                        break;
                    }
                }
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean hasClosingQuote(String text, int fromIndex) {
        int len = text.length();
        int i = fromIndex;
        while (i < len) {
            char c = text.charAt(i);
            if (c == BACKSLASH_CHAR) {
                i += 2;
                continue;
            }
            if (c == DOUBLE_QUOTE_CHAR) {
                return true;
            }
            i++;
        }
        return false;
    }

    static String buildPlantUmlSource(String rawCode, String language, boolean darkTheme) {
        if (rawCode == null || rawCode.isBlank()) {
            return "";
        }
        String normalizedLines = rawCode.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalizedLines.isEmpty()) {
            return "";
        }
        String normalizedLanguage = normalizeLanguage(language);
        String lowerCode = normalizedLines.toLowerCase(Locale.ROOT);
        if (LANG_DITAA.equals(normalizedLanguage) || lowerCode.startsWith(START_DITAA)) {
            if (lowerCode.startsWith(START_PREFIX)) {
                return normalizedLines;
            }
            return START_DITAA + "\n" + normalizedLines + "\n" + END_DITAA;
        }

        String sanitizedLines = sanitizeMultilineQuotedStrings(normalizedLines);
        String wrapped;
        if (lowerCode.startsWith(START_PREFIX)) {
            wrapped = sanitizedLines;
        } else {
            wrapped = START_UML + "\n" + sanitizedLines + "\n" + END_UML;
        }

        int firstNewline = wrapped.indexOf('\n');
        if (firstNewline < 0) {
            return wrapped;
        }
        String startLine = wrapped.substring(0, firstNewline);
        String bodyAndEnd = wrapped.substring(firstNewline + 1);
        String lowerStartLine = startLine.trim().toLowerCase(Locale.ROOT);

        StringBuilder sb = new StringBuilder(wrapped.length() + 512);
        sb.append(startLine).append('\n');
        if (lowerStartLine.startsWith(START_UML)) {
            if (!bodyAndEnd.toLowerCase(Locale.ROOT).contains("!pragma layout")) {
                sb.append(SMETANA_PRAGMA).append('\n');
            }
            if (darkTheme) {
                sb.append(DARK_THEME_DIRECTIVES);
            } else {
                sb.append(LIGHT_THEME_DIRECTIVES);
            }
        } else if (lowerStartLine.startsWith(START_MINDMAP) || lowerStartLine.startsWith(START_WBS)) {
            if (darkTheme) {
                sb.append(DARK_MINDMAP_STYLE);
            } else {
                sb.append(LIGHT_MINDMAP_STYLE);
            }
        }
        sb.append(bodyAndEnd);
        return sb.toString();
    }

    private static Optional<String> generateDiagramHtml(DiagramCacheKey key) {
        RENDER_COUNT.incrementAndGet();
        try {
            byte[] pngBytes = renderDiagramPngBytes(key);
            if (pngBytes.length == 0) {
                return Optional.empty();
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return Optional.empty();
            }
            Path outputDir = getOrCreateTempDir();
            String themeSuffix;
            if (key.dark()) {
                themeSuffix = "dark";
            } else {
                themeSuffix = "light";
            }
            String fileName = PathUtils.computeSha256Hex(key.normalizedCode(), 16) + "-" + themeSuffix + ".png";
            Path pngFile = outputDir.resolve(fileName);
            if (!Files.exists(pngFile)) {
                Files.write(pngFile, pngBytes);
                pngFile.toFile().deleteOnExit();
            }

            String fileUrl = pngFile.toUri().toURL().toExternalForm();
            String html = ("<table class='diagram-container' width='100%' border='0' cellpadding='8' cellspacing='0'>"
                    + "<tr><td align='center'><img src='" + SwingHtmlUtils.escapeHtml(fileUrl) + "' width='"
                    + image.getWidth() + "' height='" + image.getHeight() + "'></td></tr></table>").replace('\'', '"');
            return Optional.of(html);
        } catch (Exception e) {
            log.debug("Failed to render PlantUML diagram ({}): {}", key.language(), e.getMessage());
            return Optional.empty();
        }
    }

    private static byte[] renderDiagramPngBytes(DiagramCacheKey key) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        SourceStringReader reader = new SourceStringReader(key.normalizedCode());
        DiagramDescription description = reader.outputImage(baos, new FileFormatOption(FileFormat.PNG));
        byte[] pngBytes = baos.toByteArray();
        if (isValidPlantUmlOutput(description, pngBytes)) {
            return pngBytes;
        }
        if (isDitaaSource(key)) {
            String ditaaBody = extractDiagramBody(key.normalizedCode());
            if (!ditaaBody.isBlank()) {
                return renderDitaaFallbackPng(ditaaBody, key.dark());
            }
        }
        return new byte[0];
    }

    private static boolean isValidPlantUmlOutput(DiagramDescription description, byte[] pngBytes) {
        if (description == null || pngBytes == null || pngBytes.length == 0) {
            return false;
        }
        String descText = description.getDescription();
        if (descText == null || descText.isBlank()) {
            return false;
        }
        return !descText.contains(ERROR_DESCRIPTION_MARKER);
    }

    private static boolean isDitaaSource(DiagramCacheKey key) {
        if (LANG_DITAA.equals(key.language())) {
            return true;
        }
        return key.normalizedCode().toLowerCase(Locale.ROOT).startsWith(START_DITAA);
    }

    static String extractDiagramBody(String normalizedSource) {
        if (normalizedSource == null || normalizedSource.isBlank()) {
            return "";
        }
        int firstNewline = normalizedSource.indexOf('\n');
        if (firstNewline < 0) {
            return "";
        }
        int lastNewline = normalizedSource.lastIndexOf('\n');
        if (lastNewline <= firstNewline) {
            return "";
        }
        String lastLine = normalizedSource.substring(lastNewline + 1).trim().toLowerCase(Locale.ROOT);
        if (lastLine.startsWith("@end")) {
            return normalizedSource.substring(firstNewline + 1, lastNewline);
        }
        return normalizedSource.substring(firstNewline + 1);
    }

    static byte[] renderDitaaFallbackPng(String body, boolean darkTheme) throws IOException {
        String[] lines = body.split("\n", -1);
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, DITAA_FONT_SIZE);

        BufferedImage measureImg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D measureG2 = measureImg.createGraphics();
        measureG2.setFont(font);
        FontMetrics fm = measureG2.getFontMetrics();
        int maxLineWidth = 1;
        for (String line : lines) {
            int lineWidth = fm.stringWidth(line);
            if (lineWidth > maxLineWidth) {
                maxLineWidth = lineWidth;
            }
        }
        int lineHeight = Math.max(fm.getHeight(), DITAA_FONT_SIZE + 4);
        int ascent = fm.getAscent();
        measureG2.dispose();

        int width = maxLineWidth + DITAA_PADDING_X * 2;
        int height = lines.length * lineHeight + DITAA_PADDING_Y * 2;

        Color bgColor;
        Color borderColor;
        Color textColor;
        if (darkTheme) {
            bgColor = new Color(0x2D, 0x2D, 0x30);
            borderColor = new Color(0x94, 0xA3, 0xB8);
            textColor = new Color(0xE2, 0xE8, 0xF0);
        } else {
            bgColor = new Color(0xF8, 0xFA, 0xFC);
            borderColor = new Color(0x33, 0x41, 0x55);
            textColor = new Color(0x0F, 0x17, 0x2A);
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(bgColor);
        g2.fillRect(0, 0, width, height);
        g2.setColor(borderColor);
        g2.drawRect(0, 0, width - 1, height - 1);
        g2.setFont(font);
        g2.setColor(textColor);

        int y = DITAA_PADDING_Y + ascent;
        for (String line : lines) {
            g2.drawString(line, DITAA_PADDING_X, y);
            y += lineHeight;
        }
        g2.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static synchronized Path getOrCreateTempDir() throws IOException {
        if (tempDiagramDir != null && Files.isDirectory(tempDiagramDir)) {
            return tempDiagramDir;
        }
        Path created = Files.createTempDirectory(TEMP_DIR_PREFIX);
        created.toFile().deleteOnExit();
        tempDiagramDir = created;
        return created;
    }

    static void clearCacheForTesting() {
        DIAGRAM_CACHE.invalidateAll();
        RENDER_COUNT.set(0);
    }

    static int getRenderCountForTesting() {
        return RENDER_COUNT.get();
    }
}

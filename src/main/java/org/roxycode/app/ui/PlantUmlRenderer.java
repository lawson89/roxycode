package org.roxycode.app.ui;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.geometry.size.FloatSize;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.attributes.ViewBox;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.core.DiagramDescription;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders PlantUML, ditaa, and SVG diagram blocks embedded in Markdown content.
 */
public final class PlantUmlRenderer {

    private static final Logger log = LoggerFactory.getLogger(PlantUmlRenderer.class);

    static final int MAX_CACHE_ENTRIES = 200;
    /** Hyperlink prefix used on diagram footers to open a standalone diagram export in the OS browser. */
    public static final String DIAGRAM_OPEN_PREFIX = "diagram-open:";
    private static final String TEMP_DIR_PREFIX = "roxycode-diagrams-";
    private static final String LANG_PLANTUML = "plantuml";
    private static final String LANG_PUML = "puml";
    private static final String LANG_UML = "uml";
    private static final String LANG_DITAA = "ditaa";
    private static final String LANG_SVG = "svg";
    private static final Set<String> DIAGRAM_LANGUAGES = Set.of(LANG_PLANTUML, LANG_PUML, LANG_UML, LANG_DITAA,
            LANG_SVG);

    private static final String START_PREFIX = "@start";
    private static final String START_UML = "@startuml";
    private static final String END_UML = "@enduml";
    private static final String START_DITAA = "@startditaa";
    private static final String END_DITAA = "@endditaa";
    private static final String START_MINDMAP = "@startmindmap";
    private static final String START_WBS = "@startwbs";
    private static final String START_SVG_TAG = "<svg";
    private static final String END_SVG_TAG = "</svg>";
    private static final int SVG_DEFAULT_WIDTH = 600;
    private static final int SVG_DEFAULT_HEIGHT = 400;
    private static final int SVG_MAX_DIMENSION = 1600;
    private static final String SMETANA_PRAGMA = "!pragma layout smetana";
    private static final String ERROR_DESCRIPTION_MARKER = "(Error)";
    private static final char DOUBLE_QUOTE_CHAR = '"';
    private static final char BACKSLASH_CHAR = '\\';
    private static final char NEWLINE_CHAR = '\n';
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

    public record DiagramCacheKey(String normalizedCode, boolean dark, String language, String diagramId) {
        public DiagramCacheKey(String normalizedCode, boolean dark, String language) {
            this(normalizedCode, dark, language, computeDiagramId(normalizedCode, language));
        }
    }

    record DiagramExportSource(String rawCode, String language) {
    }

    private static final Cache<DiagramCacheKey, Optional<String>> DIAGRAM_CACHE = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_ENTRIES).build();
    private static final Cache<String, DiagramExportSource> DIAGRAM_SOURCE_CACHE = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_ENTRIES).build();

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
        if (lower.startsWith(START_UML) || lower.startsWith(START_DITAA) || lower.startsWith(START_MINDMAP)
                || lower.startsWith(START_WBS)) {
            return true;
        }
        return isRawSvgBlock(lower);
    }

    private static boolean isRawSvgBlock(String lowerTrimmedCode) {
        return lowerTrimmedCode.startsWith(START_SVG_TAG) && lowerTrimmedCode.endsWith(END_SVG_TAG);
    }

    static String resolveEffectiveLanguage(String language, String code) {
        String normalized = normalizeLanguage(language);
        if (LANG_SVG.equals(normalized)) {
            return LANG_SVG;
        }
        if (LANG_DITAA.equals(normalized)) {
            return LANG_DITAA;
        }
        if (code != null) {
            String lower = code.trim().toLowerCase(Locale.ROOT);
            if (isRawSvgBlock(lower)) {
                return LANG_SVG;
            }
            if (lower.startsWith(START_DITAA)) {
                return LANG_DITAA;
            }
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
        String normalizedSource;
        if (LANG_SVG.equals(effectiveLanguage)) {
            normalizedSource = code.trim();
        } else {
            normalizedSource = buildPlantUmlSource(code, effectiveLanguage, darkTheme);
        }
        if (normalizedSource.isBlank()) {
            return Optional.empty();
        }
        String diagramId = computeDiagramId(code.trim(), effectiveLanguage);
        DIAGRAM_SOURCE_CACHE.put(diagramId, new DiagramExportSource(code, effectiveLanguage));
        DiagramCacheKey cacheKey = new DiagramCacheKey(normalizedSource, darkTheme, effectiveLanguage, diagramId);
        return DIAGRAM_CACHE.get(cacheKey, PlantUmlRenderer::generateDiagramHtml);
    }

    /**
     * Renders a standalone HTML5 diagram snippet (inline vector {@code <svg>} or Base64 PNG fallback) in light theme
     * for browser/HTML5 export.
     *
     * @param code
     *            raw diagram source code
     * @param language
     *            fenced code block language hint (may be blank)
     * @return rendered {@code <div class="diagram-export">...</div>} HTML snippet, or empty if invalid
     */
    public static Optional<String> renderDiagramExportHtml(String code, String language) {
        if (code == null || code.isBlank() || !isDiagramBlock(language, code)) {
            return Optional.empty();
        }
        String effectiveLanguage = resolveEffectiveLanguage(language, code);
        if (LANG_SVG.equals(effectiveLanguage)) {
            String trimmedSvg = code.trim();
            if (trimmedSvg.isBlank()) {
                return Optional.empty();
            }
            return Optional.of("<div class='diagram-export'>" + trimmedSvg + "</div>");
        }
        String lightSource = buildPlantUmlSource(code, effectiveLanguage, false);
        if (lightSource.isBlank()) {
            return Optional.empty();
        }
        DiagramCacheKey lightKey = new DiagramCacheKey(lightSource, false, effectiveLanguage);
        try {
            if (!isDitaaSource(lightKey)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                SourceStringReader reader = new SourceStringReader(lightSource);
                DiagramDescription description = reader.outputImage(baos, new FileFormatOption(FileFormat.SVG));
                byte[] svgBytes = baos.toByteArray();
                if (isValidPlantUmlOutput(description, svgBytes)) {
                    String rawSvg = new String(svgBytes, StandardCharsets.UTF_8);
                    String cleanedSvg = rawSvg.trim();
                    if (cleanedSvg.startsWith("<?xml")) {
                        int declEnd = cleanedSvg.indexOf("?>");
                        if (declEnd >= 0) {
                            cleanedSvg = cleanedSvg.substring(declEnd + 2).trim();
                        }
                    }
                    if (cleanedSvg.toLowerCase(Locale.ROOT).contains(START_SVG_TAG)) {
                        return Optional.of("<div class='diagram-export'>" + cleanedSvg + "</div>");
                    }
                }
            }
            byte[] pngBytes = renderDiagramPngBytes(lightKey);
            if (pngBytes == null || pngBytes.length == 0) {
                return Optional.empty();
            }
            String base64 = Base64.getEncoder().encodeToString(pngBytes);
            return Optional.of(
                    "<div class='diagram-export'><img src='data:image/png;base64," + base64 + "' alt='Diagram'></div>");
        } catch (Exception e) {
            log.debug("Failed to export diagram ({}): {}", effectiveLanguage, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Renders a previously cached diagram by its 16-hex {@code diagramId} for standalone browser export.
     *
     * @param diagramId
     *            canonical 16-hex diagram identifier from a {@code diagram-open:} link
     * @return rendered {@code <div class="diagram-export">...</div>} HTML snippet, or empty if unknown/expired
     */
    public static Optional<String> renderDiagramExportHtmlById(String diagramId) {
        if (diagramId == null || diagramId.isBlank()) {
            return Optional.empty();
        }
        DiagramExportSource source = DIAGRAM_SOURCE_CACHE.getIfPresent(diagramId.trim());
        if (source == null) {
            return Optional.empty();
        }
        return renderDiagramExportHtml(source.rawCode(), source.language());
    }

    static String computeDiagramId(String rawCode, String effectiveLanguage) {
        String safeCode = "";
        if (rawCode != null) {
            safeCode = rawCode;
        }
        String safeLang = "";
        if (effectiveLanguage != null) {
            safeLang = effectiveLanguage;
        }
        return PathUtils.computeSha256Hex(safeLang + "\n" + safeCode, 8);
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
        try {
            byte[] pngBytes = renderDiagramPngBytes(key);
            Path outputDir = getOrCreateTempDir();
            String themeSuffix;
            if (key.dark()) {
                themeSuffix = "dark";
            } else {
                themeSuffix = "light";
            }
            String fileName = PathUtils.computeSha256Hex(key.normalizedCode(), 16) + "-" + themeSuffix + ".png";
            UiComponents.HtmlImagePreview preview = UiComponents.writePngBytesToTempPreview(pngBytes, outputDir,
                    fileName);
            if (preview == null) {
                return Optional.empty();
            }
            String diagramId = key.diagramId();
            if (diagramId == null || diagramId.isBlank()) {
                diagramId = computeDiagramId(key.normalizedCode(), key.language());
            }
            String html = "<table class=\"diagram-container\" width=\"100%\" border=\"0\" cellpadding=\"8\" cellspacing=\"0\"><tr><td align=\"center\"><img src=\""
                    + SwingHtmlUtils.escapeHtml(preview.url()) + "\" width=\"" + preview.width() + "\" height=\""
                    + preview.height()
                    + "\"></td></tr><tr><td align=\"center\" style=\"padding-top: 2px;\"><a class=\"action-cta-link\" href=\""
                    + DIAGRAM_OPEN_PREFIX + SwingHtmlUtils.escapeHtml(diagramId)
                    + "\">Open / Print Diagram</a></td></tr></table>";
            return Optional.of(html);
        } catch (Exception e) {
            log.debug("Failed to render PlantUML diagram ({}): {}", key.language(), e.getMessage());
            return Optional.empty();
        }
    }

    private static byte[] renderDiagramPngBytes(DiagramCacheKey key) throws IOException {
        if (LANG_SVG.equals(key.language())) {
            return renderSvgPngBytes(key.normalizedCode());
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        SourceStringReader reader = new SourceStringReader(key.normalizedCode());
        DiagramDescription description = reader.outputImage(baos, new FileFormatOption(FileFormat.PNG));
        byte[] pngBytes = baos.toByteArray();
        if (isValidPlantUmlOutput(description, pngBytes)) {
            return pngBytes;
        }
        String descText = null;
        if (description != null) {
            descText = description.getDescription();
        }
        log.debug("PlantUML syntax error: {}", descText);
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

    static byte[] renderSvgPngBytes(String svgCode) throws IOException {
        SVGLoader loader = new SVGLoader();
        SVGDocument svgDocument = loader.load(new ByteArrayInputStream(svgCode.getBytes(StandardCharsets.UTF_8)));
        if (svgDocument == null) {
            return new byte[0];
        }
        int[] dimensions = resolveSvgRenderDimensions(svgDocument.size());
        int width = dimensions[0];
        int height = dimensions[1];

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        svgDocument.render(null, g2, new ViewBox(0, 0, width, height));
        g2.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    static int[] resolveSvgRenderDimensions(FloatSize size) {
        if (size == null || size.width <= 0.0f || size.height <= 0.0f) {
            return new int[]{SVG_DEFAULT_WIDTH, SVG_DEFAULT_HEIGHT};
        }
        double width = size.width;
        double height = size.height;
        double maxSide = Math.max(width, height);
        if (maxSide > SVG_MAX_DIMENSION) {
            double scale = SVG_MAX_DIMENSION / maxSide;
            width = width * scale;
            height = height * scale;
        }
        int resolvedWidth = Math.max(1, (int) Math.round(width));
        int resolvedHeight = Math.max(1, (int) Math.round(height));
        return new int[]{resolvedWidth, resolvedHeight};
    }

    private static boolean isDitaaSource(DiagramCacheKey key) {
        if (LANG_DITAA.equals(key.language())) {
            return true;
        }
        return key.normalizedCode().toLowerCase(Locale.ROOT).startsWith(START_DITAA);
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
}

package org.roxycode.app.ui;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final String LANG_SVG = "svg";
    private static final String CSS_PROP_FONT_FAMILY = "font-family";
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
    private static final String SVG_ATTR_WIDTH = "width";
    private static final String SVG_ATTR_HEIGHT = "height";
    private static final String SVG_ATTR_VIEWBOX = "viewbox";
    private static final int SVG_VIEWBOX_TOKEN_COUNT = 4;
    private static final float SVG_DIMENSION_EPSILON = 0.001f;
    private static final Pattern SVG_OPEN_TAG_PATTERN = Pattern.compile("(?is)<svg\\b([^>]*)>");
    private static final Pattern SVG_ATTR_PATTERN = Pattern
            .compile("(?is)(?<![\\w-])(width|height|viewBox)\\s*=\\s*(?:\\x22([^\\x22]*)\\x22|'([^']*)')");
    private static final Pattern WHITESPACE_OR_COMMA_PATTERN = Pattern.compile("[\\s,]+");
    private static final Pattern SVG_STYLE_BLOCK_PATTERN = Pattern.compile("(?is)<style\\b[^>]*>(.*?)</style>");
    private static final Pattern CSS_RULE_PATTERN = Pattern.compile("(?s)([^{}]+)\\{([^{}]*)\\}");
    private static final Pattern SVG_ELEMENT_OPEN_PATTERN = Pattern.compile("(?s)<([a-zA-Z][\\w:-]*)(\\s+[^>]*?)(/?)>");
    private static final Pattern SVG_CLASS_ATTR_PATTERN = Pattern
            .compile("(?is)(?<![\\w-])class\\s*=\\s*(?:\\x22([^\\x22]*)\\x22|'([^']*)')");
    private static final Pattern SVG_TEXT_BLOCK_PATTERN = Pattern.compile("(?is)<text\\b[^>]*>.*?</text>");
    private static final Pattern XML_TAG_OR_TEXT_PATTERN = Pattern
            .compile("(?s)(</?[a-zA-Z!?][^>]*>)|((?:[^<]|<(?![/?!a-zA-Z]))+)");
    private static final Pattern BARE_AMPERSAND_PATTERN = Pattern
            .compile("&(?!(?:#[0-9]+|#x[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9._-]*);)");
    private static final Set<String> SUPPORTED_SVG_PRESENTATION_ATTRS = Set.of("fill", "stroke", "stroke-width",
            "font-family", "font-size", "font-weight", "rx", "ry", "opacity", "stroke-dasharray", "text-anchor",
            "dominant-baseline", "fill-opacity", "stroke-opacity");
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
            String html = "<table class=\"diagram-container\" width=\"100%\" border=\"0\" cellpadding=\"8\" cellspacing=\"0\"><tr><td align=\"center\"><img src=\""
                    + SwingHtmlUtils.escapeHtml(preview.url()) + "\" width=\"" + preview.width() + "\" height=\""
                    + preview.height() + "\"></td></tr></table>";
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

    static byte[] renderSvgPngBytes(String svgCode) throws IOException {
        String normalizedSvg = normalizeRootSvgAttributes(svgCode);
        SVGLoader loader = new SVGLoader();
        SVGDocument svgDocument = loader.load(new ByteArrayInputStream(normalizedSvg.getBytes(StandardCharsets.UTF_8)));
        if (svgDocument == null) {
            return new byte[0];
        }
        FloatSize size = resolveIntrinsicSvgSize(normalizedSvg, svgDocument.size());
        int[] dimensions = resolveSvgRenderDimensions(size);
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

    static String sanitizeSvgForRendering(String svgCode) {
        if (svgCode == null || svgCode.isBlank()) {
            return svgCode;
        }
        String inlined = inlineSvgStyleClasses(svgCode);
        return escapeSvgTextContent(inlined);
    }

    private static String inlineSvgStyleClasses(String svgCode) {
        Map<String, Map<String, String>> classRules = new LinkedHashMap<>();
        Matcher styleMatcher = SVG_STYLE_BLOCK_PATTERN.matcher(svgCode);
        StringBuilder stripped = new StringBuilder(svgCode.length());
        while (styleMatcher.find()) {
            parseCssClassRules(styleMatcher.group(1), classRules);
            styleMatcher.appendReplacement(stripped, "");
        }
        styleMatcher.appendTail(stripped);
        String withoutStyles = stripped.toString();
        if (classRules.isEmpty()) {
            return withoutStyles;
        }
        Matcher elemMatcher = SVG_ELEMENT_OPEN_PATTERN.matcher(withoutStyles);
        StringBuilder result = new StringBuilder(withoutStyles.length() + 128);
        while (elemMatcher.find()) {
            String tagName = elemMatcher.group(1);
            String attrs = elemMatcher.group(2);
            String selfClose = elemMatcher.group(3);
            Matcher classMatcher = SVG_CLASS_ATTR_PATTERN.matcher(attrs);
            if (!classMatcher.find()) {
                elemMatcher.appendReplacement(result, Matcher.quoteReplacement(elemMatcher.group(0)));
                continue;
            }
            String classListRaw = classMatcher.group(1);
            if (classListRaw == null) {
                classListRaw = classMatcher.group(2);
            }
            if (classListRaw == null || classListRaw.isBlank()) {
                elemMatcher.appendReplacement(result, Matcher.quoteReplacement(elemMatcher.group(0)));
                continue;
            }
            Map<String, String> mergedProps = new LinkedHashMap<>();
            for (String token : classListRaw.trim().split("\\s+")) {
                Map<String, String> ruleProps = classRules.get(token);
                if (ruleProps != null) {
                    mergedProps.putAll(ruleProps);
                }
            }
            if (mergedProps.isEmpty()) {
                elemMatcher.appendReplacement(result, Matcher.quoteReplacement(elemMatcher.group(0)));
                continue;
            }
            StringBuilder appendedAttrs = new StringBuilder(attrs);
            for (Map.Entry<String, String> prop : mergedProps.entrySet()) {
                String attrName = prop.getKey();
                if (!hasInlineSvgAttribute(attrs, attrName)) {
                    String safeValue = prop.getValue().replace("\"", "&quot;");
                    appendedAttrs.append(' ').append(attrName).append("=\"").append(safeValue).append('"');
                }
            }

            String replacement = "<" + tagName + appendedAttrs + selfClose + ">";
            elemMatcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        elemMatcher.appendTail(result);
        return result.toString();
    }

    private static void parseCssClassRules(String cssBody, Map<String, Map<String, String>> classRules) {
        if (cssBody == null || cssBody.isBlank()) {
            return;
        }
        Matcher ruleMatcher = CSS_RULE_PATTERN.matcher(cssBody);
        while (ruleMatcher.find()) {
            String selectorGroup = ruleMatcher.group(1).trim();
            String declarations = ruleMatcher.group(2).trim();
            Map<String, String> parsedProps = parseCssDeclarations(declarations);
            if (parsedProps.isEmpty()) {
                continue;
            }
            for (String selector : selectorGroup.split(",")) {
                String trimmedSelector = selector.trim();
                if (trimmedSelector.startsWith(".") && trimmedSelector.length() > 1) {
                    String className = trimmedSelector.substring(1).trim();
                    if (!className.isEmpty() && className.indexOf(' ') < 0 && className.indexOf(':') < 0) {
                        classRules.computeIfAbsent(className, k -> new LinkedHashMap<>()).putAll(parsedProps);
                    }
                }
            }
        }
    }

    private static Map<String, String> parseCssDeclarations(String declarations) {
        Map<String, String> props = new LinkedHashMap<>();
        if (declarations == null || declarations.isBlank()) {
            return props;
        }
        for (String decl : declarations.split(";")) {
            int colonIdx = decl.indexOf(':');
            if (colonIdx <= 0) {
                continue;
            }
            String propName = decl.substring(0, colonIdx).trim().toLowerCase(Locale.ROOT);
            if (!SUPPORTED_SVG_PRESENTATION_ATTRS.contains(propName)) {
                continue;
            }
            String propVal = decl.substring(colonIdx + 1).trim();
            if (CSS_PROP_FONT_FAMILY.equals(propName)) {
                propVal = propVal.replace("'", "").replace("\"", "");
            }
            props.put(propName, propVal);
        }
        return props;
    }

    private static boolean hasInlineSvgAttribute(String attrs, String attrName) {
        Pattern p = Pattern.compile("(?is)(?<![\\w-])" + Pattern.quote(attrName) + "\\s*=");
        return p.matcher(attrs).find();

    }

    private static String escapeSvgTextContent(String svgCode) {
        Matcher textMatcher = SVG_TEXT_BLOCK_PATTERN.matcher(svgCode);
        StringBuilder result = new StringBuilder(svgCode.length());
        while (textMatcher.find()) {
            String textBlock = textMatcher.group(0);
            String escapedBlock = escapeXmlTextSegments(textBlock);
            textMatcher.appendReplacement(result, Matcher.quoteReplacement(escapedBlock));
        }
        textMatcher.appendTail(result);
        return result.toString();
    }

    private static String escapeXmlTextSegments(String textBlock) {
        Matcher segmentMatcher = XML_TAG_OR_TEXT_PATTERN.matcher(textBlock);
        StringBuilder out = new StringBuilder(textBlock.length());
        while (segmentMatcher.find()) {
            String tag = segmentMatcher.group(1);
            if (tag != null) {
                out.append(tag);
            } else {
                String text = segmentMatcher.group(2);
                if (text != null) {
                    String escapedAmp = BARE_AMPERSAND_PATTERN.matcher(text).replaceAll("&amp;");
                    String escapedLt = escapedAmp.replace("<", "&lt;");
                    out.append(escapedLt);
                }
            }
        }
        return out.toString();
    }

    static String normalizeRootSvgAttributes(String svgCode) {
        if (svgCode == null || svgCode.isBlank()) {
            return svgCode;
        }
        String sanitized = sanitizeSvgForRendering(svgCode);
        Matcher openTagMatcher = SVG_OPEN_TAG_PATTERN.matcher(sanitized);
        if (!openTagMatcher.find()) {
            return sanitized;
        }
        String attrs = openTagMatcher.group(1);
        SvgRootAttributes parsed = parseSvgRootAttributes(attrs);
        boolean widthIsPercent = isPercentageValue(parsed.width());
        boolean heightIsPercent = isPercentageValue(parsed.height());
        if (!widthIsPercent && !heightIsPercent) {
            return sanitized;
        }
        FloatSize viewBoxSize = parseViewBoxDimensions(parsed.viewBox());
        String updatedAttrs = attrs;
        if (widthIsPercent) {
            float fallbackWidth;
            if (viewBoxSize != null) {
                fallbackWidth = viewBoxSize.width;
            } else {
                fallbackWidth = SVG_DEFAULT_WIDTH;
            }
            float resolvedWidth = scaleByPercentage(fallbackWidth, parsed.width());
            updatedAttrs = replaceSvgAttributeValue(updatedAttrs, SVG_ATTR_WIDTH, formatSvgDimension(resolvedWidth));
        }
        if (heightIsPercent) {
            float fallbackHeight;
            if (viewBoxSize != null) {
                fallbackHeight = viewBoxSize.height;
            } else {
                fallbackHeight = SVG_DEFAULT_HEIGHT;
            }
            float resolvedHeight = scaleByPercentage(fallbackHeight, parsed.height());
            updatedAttrs = replaceSvgAttributeValue(updatedAttrs, SVG_ATTR_HEIGHT, formatSvgDimension(resolvedHeight));
        }
        StringBuilder sb = new StringBuilder(sanitized.length() + 16);
        sb.append(sanitized, 0, openTagMatcher.start(1));
        sb.append(updatedAttrs);
        sb.append(sanitized, openTagMatcher.end(1), sanitized.length());
        return sb.toString();
    }

    static FloatSize resolveIntrinsicSvgSize(String svgCode, FloatSize documentSize) {
        if (svgCode == null || svgCode.isBlank()) {
            return documentSize;
        }
        Matcher openTagMatcher = SVG_OPEN_TAG_PATTERN.matcher(svgCode);
        if (!openTagMatcher.find()) {
            return documentSize;
        }
        SvgRootAttributes parsed = parseSvgRootAttributes(openTagMatcher.group(1));
        FloatSize viewBoxSize = parseViewBoxDimensions(parsed.viewBox());
        float vbWidth = -1.0f;
        float vbHeight = -1.0f;
        if (viewBoxSize != null) {
            vbWidth = viewBoxSize.width;
            vbHeight = viewBoxSize.height;
        }
        float docWidth = -1.0f;
        float docHeight = -1.0f;
        if (documentSize != null) {
            docWidth = documentSize.width;
            docHeight = documentSize.height;
        }
        float resolvedWidth = resolveAxisDimension(parsed.width(), vbWidth, docWidth, SVG_DEFAULT_WIDTH);
        float resolvedHeight = resolveAxisDimension(parsed.height(), vbHeight, docHeight, SVG_DEFAULT_HEIGHT);
        return new FloatSize(resolvedWidth, resolvedHeight);
    }

    private record SvgRootAttributes(String width, String height, String viewBox) {
    }

    private static SvgRootAttributes parseSvgRootAttributes(String attrs) {
        String width = null;
        String height = null;
        String viewBox = null;
        Matcher attrMatcher = SVG_ATTR_PATTERN.matcher(attrs);
        while (attrMatcher.find()) {
            String name = attrMatcher.group(1).toLowerCase(Locale.ROOT);
            String value = attrMatcher.group(2);
            if (value == null) {
                value = attrMatcher.group(3);
            }
            if (SVG_ATTR_WIDTH.equals(name)) {
                width = value;
            } else if (SVG_ATTR_HEIGHT.equals(name)) {
                height = value;
            } else if (SVG_ATTR_VIEWBOX.equals(name)) {
                viewBox = value;
            }
        }
        return new SvgRootAttributes(width, height, viewBox);
    }

    private static FloatSize parseViewBoxDimensions(String viewBoxValue) {
        if (viewBoxValue == null || viewBoxValue.isBlank()) {
            return null;
        }
        String[] parts = WHITESPACE_OR_COMMA_PATTERN.split(viewBoxValue.trim());
        if (parts.length != SVG_VIEWBOX_TOKEN_COUNT) {
            return null;
        }
        try {
            double vbWidth = Double.parseDouble(parts[2]);
            double vbHeight = Double.parseDouble(parts[3]);
            if (Double.isFinite(vbWidth) && Double.isFinite(vbHeight) && vbWidth > 0.0 && vbHeight > 0.0) {
                return new FloatSize((float) vbWidth, (float) vbHeight);
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private static boolean isPositiveDimension(float value) {
        return Float.isFinite(value) && value > 0;
    }

    private static float resolveAxisDimension(String attrValue, float viewBoxDim, float docDim, float defaultDim) {
        if (attrValue == null || attrValue.isBlank()) {
            if (isPositiveDimension(viewBoxDim)) {
                return viewBoxDim;
            }
            if (isPositiveDimension(docDim)) {
                return docDim;
            }
            return defaultDim;
        }
        if (isPercentageValue(attrValue)) {
            if (isPositiveDimension(viewBoxDim)) {
                return scaleByPercentage(viewBoxDim, attrValue);
            }
            return scaleByPercentage(defaultDim, attrValue);
        }
        if (isPositiveDimension(docDim)) {
            return docDim;
        }
        if (isPositiveDimension(viewBoxDim)) {
            return viewBoxDim;
        }
        return defaultDim;
    }

    private static boolean isPercentageValue(String attrValue) {
        return attrValue != null && attrValue.trim().endsWith("%");
    }

    private static float scaleByPercentage(float baseValue, String percentAttr) {
        String trimmed = percentAttr.trim();
        String numericPart = trimmed.substring(0, trimmed.length() - 1).trim();
        try {
            double percent = Double.parseDouble(numericPart);
            if (Double.isFinite(percent) && percent > 0.0) {
                return (float) (baseValue * (percent / 100.0));
            }
        } catch (NumberFormatException ignored) {
            return baseValue;
        }
        return baseValue;
    }

    private static String replaceSvgAttributeValue(String attrs, String attrName, String newValue) {
        Pattern singleAttrPattern = Pattern
                .compile("(?is)((?<![\\w-])" + attrName + "\\s*=\\s*)(?:\\x22[^\\x22]*\\x22|'[^']*')");
        Matcher matcher = singleAttrPattern.matcher(attrs);
        if (!matcher.find()) {
            return attrs;
        }
        String replacement = matcher.group(1) + '"' + newValue + '"';
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private static String formatSvgDimension(float value) {
        long rounded = Math.round(value);
        if (Math.abs(value - rounded) < SVG_DIMENSION_EPSILON) {
            return Long.toString(rounded);
        }
        return Float.toString(value);
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

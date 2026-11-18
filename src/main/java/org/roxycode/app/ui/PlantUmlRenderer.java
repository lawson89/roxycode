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
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.ast.Node;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import net.sourceforge.plantuml.BlockUml;
import net.sourceforge.plantuml.ErrorUml;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.core.DiagramDescription;
import net.sourceforge.plantuml.error.PSystemError;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

/**
 * Renders and validates PlantUML, ditaa, and SVG diagram blocks embedded in Markdown content.
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
    private static final Pattern SVG_TEXT_BLOCK_PATTERN = Pattern
            .compile("(?is)(<(text|title|desc)\\b[^>/]*>)(.*?)(</\\2\\s*>)");
    private static final Pattern TSPAN_TAG_PATTERN = Pattern.compile("(?is)</?tspan\\b[^>]*>");
    private static final Pattern BARE_AMPERSAND_PATTERN = Pattern
            .compile("&(?!(?:#[0-9]+|#x[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9._-]*);)");
    private static final Set<String> SUPPORTED_SVG_PRESENTATION_ATTRS = Set.of("fill", "stroke", "stroke-width",
            "font-family", "font-size", "font-weight", "rx", "ry", "opacity", "stroke-dasharray", "text-anchor",
            "dominant-baseline", "fill-opacity", "stroke-opacity");
    private static final String SMETANA_PRAGMA = "!pragma layout smetana";
    private static final String SVG_TEXT_TAG = "text";
    private static final String ERROR_DESCRIPTION_MARKER = "(Error)";
    private static final char DOUBLE_QUOTE_CHAR = '"';
    private static final char BACKSLASH_CHAR = '\\';
    private static final char NEWLINE_CHAR = '\n';
    private static final String CRLF = "\r\n";

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

    public record DiagramCacheKey(String normalizedCode, boolean dark, String language, String diagramId) {
        public DiagramCacheKey(String normalizedCode, boolean dark, String language) {
            this(normalizedCode, dark, language, computeDiagramId(normalizedCode, language));
        }
    }

    record DiagramExportSource(String rawCode, String language) {
    }

    /**
     * Structured diagnostic describing a syntax or rendering error in a Markdown diagram block.
     *
     * @param blockIndex
     *            1-based index of the diagram block in the Markdown document
     * @param language
     *            resolved diagram language (e.g., {@code plantuml}, {@code svg}, {@code ditaa})
     * @param lineNumber
     *            1-based line number within the diagram block where the error occurred
     * @param offendingLine
     *            source line at or near the reported error
     * @param errorMessage
     *            human-readable parser or renderer diagnostic message
     */
    public record DiagramValidationError(int blockIndex, String language, int lineNumber, String offendingLine,
            String errorMessage) {
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

    /**
     * Parses Markdown content and validates every embedded PlantUML, ditaa, and SVG diagram block.
     *
     * @param markdown
     *            the Markdown text to inspect
     * @return a list of structured validation errors, or an empty list if all diagrams are valid or none exist
     */
    public static List<DiagramValidationError> validateMarkdownDiagrams(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        String lower = markdown.toLowerCase(Locale.ROOT);
        if (!markdown.contains("```") && !markdown.contains("~~~") && !lower.contains(START_PREFIX)
                && !lower.contains(START_SVG_TAG)) {
            return List.of();
        }
        Node document = SwingHtmlUtils.getMarkdownParser().parse(markdown);
        List<DiagramValidationError> errors = new ArrayList<>();
        int[] blockCounter = new int[]{0};
        collectAndValidateDiagramNodes(document, blockCounter, errors);
        return errors;
    }

    private static void collectAndValidateDiagramNodes(Node node, int[] blockCounter,
            List<DiagramValidationError> errors) {
        if (node == null) {
            return;
        }
        if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
            String code = ((Block) node).getContentChars().toString();
            String language = "";
            if (node instanceof FencedCodeBlock fenced) {
                language = fenced.getInfo().toString();
            }
            if (isDiagramBlock(language, code)) {
                blockCounter[0] = blockCounter[0] + 1;
                validateSingleDiagramBlock(blockCounter[0], language, code, errors);
            }
            return;
        }
        Node child = node.getFirstChild();
        while (child != null) {
            collectAndValidateDiagramNodes(child, blockCounter, errors);
            child = child.getNext();
        }
    }

    private static void validateSingleDiagramBlock(int blockIndex, String language, String code,
            List<DiagramValidationError> errors) {
        String effectiveLanguage = resolveEffectiveLanguage(language, code);
        if (LANG_DITAA.equals(effectiveLanguage)) {
            String ditaaSource = buildPlantUmlSource(code, LANG_DITAA, false);
            if (extractDiagramBody(ditaaSource).isBlank()) {
                errors.add(new DiagramValidationError(blockIndex, effectiveLanguage, 1, "",
                        "ditaa diagram block is empty."));
            }
            return;
        }
        if (LANG_SVG.equals(effectiveLanguage)) {
            validateSvgBlock(blockIndex, code, errors);
            return;
        }
        validatePlantUmlBlock(blockIndex, effectiveLanguage, code, errors);
    }

    private static void validatePlantUmlBlock(int blockIndex, String effectiveLanguage, String code,
            List<DiagramValidationError> errors) {
        String source = buildPlantUmlSource(code, effectiveLanguage, false);
        if (source.isBlank()) {
            errors.add(new DiagramValidationError(blockIndex, effectiveLanguage, 1, "",
                    "PlantUML diagram block is empty."));
            return;
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            SourceStringReader reader = new SourceStringReader(source);
            DiagramDescription description = reader.outputImage(baos, new FileFormatOption(FileFormat.PNG));
            byte[] pngBytes = baos.toByteArray();
            if (isValidPlantUmlOutput(description, pngBytes)) {
                return;
            }
            DiagramValidationError parsedError = extractPlantUmlError(blockIndex, effectiveLanguage, code, source,
                    reader, description);
            errors.add(parsedError);
        } catch (IOException e) {
            errors.add(new DiagramValidationError(blockIndex, effectiveLanguage, 1, firstNonBlankLine(code),
                    "Failed to validate PlantUML diagram: " + e.getMessage()));
        }
    }

    private static DiagramValidationError extractPlantUmlError(int blockIndex, String effectiveLanguage, String rawCode,
            String builtSource, SourceStringReader reader, DiagramDescription description) {
        String normalizedRaw = rawCode.replace(CRLF, "\n").replace('\r', '\n').trim();
        String[] rawLines = normalizedRaw.split("\n", -1);
        String[] builtLines = builtSource.split("\n", -1);
        int injectedHeaderLines = countInjectedPlantUmlHeaderLines(builtLines, normalizedRaw);

        for (BlockUml block : reader.getBlocks()) {
            if (block.getDiagram() instanceof PSystemError pSystemError) {
                int builtZeroBasedLine = 0;
                if (pSystemError.getLineLocation() != null) {
                    builtZeroBasedLine = pSystemError.getLineLocation().getPosition();
                }
                int mappedLineNumber = mapBuiltLineToOriginal(builtZeroBasedLine, injectedHeaderLines,
                        normalizedRaw.toLowerCase(Locale.ROOT).startsWith(START_PREFIX), rawLines.length);
                String offendingLine = resolveOffendingLine(rawLines, builtLines, mappedLineNumber, builtZeroBasedLine);
                String errorMsg = extractPSystemErrorMessage(pSystemError, description);
                return new DiagramValidationError(blockIndex, effectiveLanguage, mappedLineNumber, offendingLine,
                        errorMsg);
            }
        }
        String descText = "Unknown PlantUML syntax error";
        if (description != null && description.getDescription() != null && !description.getDescription().isBlank()) {
            descText = description.getDescription();
        }
        return new DiagramValidationError(blockIndex, effectiveLanguage, 1, firstNonBlankLine(rawCode), descText);
    }

    private static int countInjectedPlantUmlHeaderLines(String[] builtLines, String normalizedRaw) {
        boolean hadStartPrefix = normalizedRaw.toLowerCase(Locale.ROOT).startsWith(START_PREFIX);
        if (!hadStartPrefix) {
            // Wrapped with @startuml + injected directives before original line 1
            int count = 0;
            String firstOriginalLine = "";
            String[] rawLines = normalizedRaw.split("\n", -1);
            if (rawLines.length > 0) {
                firstOriginalLine = rawLines[0].trim();
            }
            for (int i = 1; i < builtLines.length; i++) {
                if (builtLines[i].trim().equals(firstOriginalLine)) {
                    return count;
                }
                count++;
            }
            return 0;
        }
        String[] rawLines = normalizedRaw.split("\n", -1);
        int minLinesWithBody = 2;
        if (rawLines.length < minLinesWithBody) {
            return 0;
        }
        String secondOriginalLine = rawLines[1].trim();
        int count = 0;
        for (int i = 1; i < builtLines.length; i++) {
            if (builtLines[i].trim().equals(secondOriginalLine)) {
                return count;
            }
            count++;
        }
        return 0;
    }

    private static int mapBuiltLineToOriginal(int builtZeroBasedLine, int injectedHeaderLines, boolean hadStartPrefix,
            int rawLineCount) {
        int originalLine;
        if (hadStartPrefix) {
            if (builtZeroBasedLine <= injectedHeaderLines) {
                originalLine = 1;
            } else {
                originalLine = builtZeroBasedLine - injectedHeaderLines + 1;
            }
        } else {
            if (builtZeroBasedLine <= injectedHeaderLines) {
                originalLine = 1;
            } else {
                originalLine = builtZeroBasedLine - injectedHeaderLines;
            }
        }
        int clampedLine = Math.max(1, originalLine);
        if (rawLineCount > 0 && clampedLine > rawLineCount) {
            return rawLineCount;
        }
        return clampedLine;
    }

    private static String resolveOffendingLine(String[] rawLines, String[] builtLines, int mappedLineNumber,
            int builtZeroBasedLine) {
        int rawIdx = mappedLineNumber - 1;
        if (rawIdx >= 0 && rawIdx < rawLines.length && !rawLines[rawIdx].isBlank()) {
            return rawLines[rawIdx].trim();
        }
        if (builtZeroBasedLine >= 0 && builtZeroBasedLine < builtLines.length) {
            return builtLines[builtZeroBasedLine].trim();
        }
        return "";
    }

    private static String extractPSystemErrorMessage(PSystemError pSystemError, DiagramDescription description) {
        Collection<ErrorUml> errorsUml = pSystemError.getErrorsUml();
        if (errorsUml != null && !errorsUml.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ErrorUml err : errorsUml) {
                if (err != null && err.getError() != null && !err.getError().isBlank()) {
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(err.getError().trim());
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        if (description != null && description.getDescription() != null && !description.getDescription().isBlank()) {
            return description.getDescription();
        }
        return "PlantUML syntax error";
    }

    private static void validateSvgBlock(int blockIndex, String code, List<DiagramValidationError> errors) {
        if (code == null || code.isBlank()) {
            errors.add(new DiagramValidationError(blockIndex, LANG_SVG, 1, "", "SVG diagram block is empty."));
            return;
        }
        String normalizedSvg = normalizeRootSvgAttributes(code.trim());
        String[] svgLines = normalizedSvg.replace(CRLF, "\n").replace('\r', '\n').split("\n", -1);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                    // Ignore warnings
                }

                @Override
                public void error(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }
            });
            builder.parse(new InputSource(new StringReader(normalizedSvg)));
        } catch (SAXParseException e) {
            int lineNum = Math.max(1, e.getLineNumber());
            String offending = "";
            if (lineNum - 1 >= 0 && lineNum - 1 < svgLines.length) {
                offending = svgLines[lineNum - 1].trim();
            }
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = "Malformed SVG XML";
            }
            errors.add(new DiagramValidationError(blockIndex, LANG_SVG, lineNum, offending, message));
            return;
        } catch (Exception e) {
            errors.add(new DiagramValidationError(blockIndex, LANG_SVG, 1, firstNonBlankLine(normalizedSvg),
                    "Invalid SVG XML: " + e.getMessage()));
            return;
        }
        SVGLoader loader = new SVGLoader();
        SVGDocument svgDocument = loader.load(new ByteArrayInputStream(normalizedSvg.getBytes(StandardCharsets.UTF_8)));
        if (svgDocument == null) {
            errors.add(new DiagramValidationError(blockIndex, LANG_SVG, 1, firstNonBlankLine(normalizedSvg),
                    "SVGLoader failed to parse SVG document."));
        }
    }

    private static String firstNonBlankLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.replace(CRLF, "\n").replace('\r', '\n').split("\n", -1);
        for (String line : lines) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return "";
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
            String sanitizedSvg = sanitizeSvgForRendering(code.trim());
            if (sanitizedSvg.isBlank()) {
                return Optional.empty();
            }
            return Optional.of("<div class='diagram-export'>" + sanitizedSvg + "</div>");
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
        String escapedText = escapeSvgTextContent(svgCode);
        return inlineSvgStyleClasses(escapedText);
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
            if (attrs.endsWith("/")) {
                attrs = attrs.substring(0, attrs.length() - 1);
                selfClose = "/";
            }
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
            String openTag = textMatcher.group(1);
            String tagName = textMatcher.group(2);
            String innerBody = textMatcher.group(3);
            String closeTag = textMatcher.group(4);
            String escapedBody;
            if (SVG_TEXT_TAG.equalsIgnoreCase(tagName)) {
                escapedBody = escapeXmlTextSegments(innerBody);
            } else {
                escapedBody = escapePlainTextSegment(innerBody);
            }
            String replacement = openTag + escapedBody + closeTag;
            textMatcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        textMatcher.appendTail(result);
        return result.toString();
    }

    private static String escapeXmlTextSegments(String innerBody) {
        if (!hasBalancedTspanTags(innerBody)) {
            return escapePlainTextSegment(innerBody);
        }
        Matcher tspanMatcher = TSPAN_TAG_PATTERN.matcher(innerBody);
        StringBuilder out = new StringBuilder(innerBody.length());
        int lastEnd = 0;
        while (tspanMatcher.find()) {
            if (tspanMatcher.start() > lastEnd) {
                out.append(escapePlainTextSegment(innerBody.substring(lastEnd, tspanMatcher.start())));
            }
            out.append(tspanMatcher.group());
            lastEnd = tspanMatcher.end();
        }
        if (lastEnd < innerBody.length()) {
            out.append(escapePlainTextSegment(innerBody.substring(lastEnd)));
        }
        return out.toString();
    }

    private static boolean hasBalancedTspanTags(String innerBody) {
        Matcher tspanMatcher = TSPAN_TAG_PATTERN.matcher(innerBody);
        int depth = 0;
        while (tspanMatcher.find()) {
            String tag = tspanMatcher.group();
            if (tag.startsWith("</")) {
                depth--;
                if (depth < 0) {
                    return false;
                }
            } else if (!tag.endsWith("/>")) {
                depth++;
            }
        }
        return depth == 0;
    }

    private static String escapePlainTextSegment(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String escapedAmp = BARE_AMPERSAND_PATTERN.matcher(text).replaceAll("&amp;");
        return escapedAmp.replace("<", "&lt;");
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
}

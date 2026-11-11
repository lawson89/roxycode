package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import org.junit.jupiter.api.Test;

class SwingHtmlUtilsTest {

    @Test
    void testRenderMarkdownToHtml() {
        assertEquals("", SwingHtmlUtils.renderMarkdownToHtml(null));
        assertEquals("", SwingHtmlUtils.renderMarkdownToHtml(""));

        String html = SwingHtmlUtils.renderMarkdownToHtml("""
                # Heading

                Use `inline<Code>` here.

                | Name | Value |
                | :--- | ---: |
                | Key | 42 |

                ---

                ~~~java
                public class Demo {}
                ~~~
                """);
        assertContainsAll(html, "<h1>Heading</h1>", "<code>inline&lt;Code&gt;</code>", "class='hr-line'", "md-table",
                "cellpadding", "md-th", "md-td", "<span class='mtk-keyword'>public</span>");
        assertFalse(html.contains("<hr"));
    }

    @Test
    void testStyleMarkdownTablesViaFlexmarkAttributeProviderDoesNotMutateCodeOrInlineHtml() {
        String rendered = SwingHtmlUtils.renderMarkdownToHtml("""
                | Col A | Col B |
                | :--- | ---: |
                | Val 1 | Val 2 |

                <table><tr><td>Inline raw HTML table</td></tr></table>

                ~~~html
                <table>
                  <tr><th>Header</th><td>Cell</td></tr>
                </table>
                ~~~
                """);
        assertContainsAll(rendered, "md-table", "md-th", "md-td", "right",
                "&lt;table&gt;&lt;tr&gt;&lt;td&gt;Inline raw HTML table&lt;/td&gt;&lt;/tr&gt;&lt;/table&gt;",
                "&lt;table&gt;");
    }

    @Test
    void testRenderMarkdownPlantUmlAndDitaaDiagramsWithFallbackOnInvalidSyntax() {
        String validDiagramMd = """
                ~~~plantuml
                Alice -> Bob: Hello
                ~~~

                ~~~ditaa
                +-----+
                | Box |
                +-----+
                ~~~
                """;
        String renderedValid = SwingHtmlUtils.renderMarkdownToHtml(validDiagramMd);
        assertContainsAll(renderedValid, "diagram-container", "<img src=", "file:");

        String invalidDiagramMd = """
                ~~~plantuml
                invalid plantuml syntax @@@
                ~~~
                """;
        String renderedInvalid = SwingHtmlUtils.renderMarkdownToHtml(invalidDiagramMd);
        assertContainsAll(renderedInvalid, "<pre class='jexl-highlight'><code>", "invalid plantuml syntax @@@");
        assertFalse(renderedInvalid.contains("mtk-keyword"));
    }

    @Test
    void testResponsiveImageViewScalesDownWideImagesAndPreservesNativeSizeWhenNarrower() throws Exception {
        java.nio.file.Path tempPng = java.nio.file.Files.createTempFile("roxycode-test-img-", ".png");
        tempPng.toFile().deleteOnExit();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(800, 400,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javax.imageio.ImageIO.write(img, "png", tempPng.toFile());
        String fileUrl = tempPng.toUri().toURL().toExternalForm();

        org.roxycode.app.util.Edt.runAndWait(() -> {
            assertFalse(SwingHtmlUtils.invalidateResponsiveImages(null));

            javax.swing.JEditorPane pane = SwingHtmlUtils.createHtmlEditorPane();
            pane.setBorder(javax.swing.BorderFactory.createEmptyBorder());
            SwingHtmlUtils.applyTheme(pane, 10);
            pane.setText("<html><body><p>Plain text without images</p></body></html>");
            pane.setSize(448, 600);
            assertFalse(SwingHtmlUtils.invalidateResponsiveImages(pane));

            pane.setText("<html><body><table width='100%'><tr><td><img src='" + fileUrl
                    + "' width='800' height='400'></td></tr></table></body></html>");

            // 1. Container narrower than image (448px -> availableWidth = 448 - 48 = 400px -> 0.5x scale)
            pane.setSize(448, 600);
            assertTrue(SwingHtmlUtils.invalidateResponsiveImages(pane));

            javax.swing.text.View rootView = pane.getUI().getRootView(pane);
            SwingHtmlUtils.ResponsiveImageView imageView = findResponsiveImageView(rootView);
            assertNotNull(imageView);
            assertTrue(imageView.getLoadsSynchronously());
            assertEquals(0f, imageView.getMinimumSpan(javax.swing.text.View.X_AXIS));
            assertEquals(400f, imageView.getPreferredSpan(javax.swing.text.View.X_AXIS));
            assertEquals(200f, imageView.getPreferredSpan(javax.swing.text.View.Y_AXIS));
            assertEquals(400f, imageView.getMaximumSpan(javax.swing.text.View.X_AXIS));
            assertEquals(200f, imageView.getMaximumSpan(javax.swing.text.View.Y_AXIS));
            assertEquals(200f, imageView.getMinimumSpan(javax.swing.text.View.Y_AXIS));

            // Verify painting into scaled bounds succeeds without error
            java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(450, 300,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2 = canvas.createGraphics();
            try {
                imageView.paint(g2, new java.awt.Rectangle(10, 10, 400, 200));
            } finally {
                g2.dispose();
            }

            // 2. Container widened (648px -> availableWidth = 600px -> 0.75x scale)
            pane.setSize(648, 600);
            SwingHtmlUtils.invalidateResponsiveImages(pane);
            assertEquals(600f, imageView.getPreferredSpan(javax.swing.text.View.X_AXIS));
            assertEquals(300f, imageView.getPreferredSpan(javax.swing.text.View.Y_AXIS));

            // 3. Container wider than image (1000px -> availableWidth = 952px >= 800px -> 1:1 native dimensions)
            pane.setSize(1000, 600);
            SwingHtmlUtils.invalidateResponsiveImages(pane);
            assertEquals(800f, imageView.getPreferredSpan(javax.swing.text.View.X_AXIS));
            assertEquals(400f, imageView.getPreferredSpan(javax.swing.text.View.Y_AXIS));
        });
    }

    @Test
    void testWrappingHtmlFactoryPreservesBrAndListAndBodyViews() throws Exception {
        javax.swing.text.html.HTMLEditorKit kit = new javax.swing.text.html.HTMLEditorKit();
        javax.swing.text.html.HTMLDocument doc = (javax.swing.text.html.HTMLDocument) kit.createDefaultDocument();
        kit.read(new java.io.StringReader("<html><body><p>Hello</p></body></html>"), doc, 0);

        javax.swing.text.Element root = doc.getDefaultRootElement();
        javax.swing.text.Element bodyElem = null;
        for (int i = 0; i < root.getElementCount(); i++) {
            javax.swing.text.Element child = root.getElement(i);
            if ("body".equalsIgnoreCase(child.getName())) {
                bodyElem = child;
                break;
            }
        }
        assertNotNull(bodyElem);
        javax.swing.text.Element pElem = bodyElem.getElement(0);
        javax.swing.text.Element contentElem = pElem.getElement(0);

        javax.swing.text.html.InlineView inlineView = new javax.swing.text.html.InlineView(contentElem);
        javax.swing.text.View wrappedInline = SwingHtmlUtils.wrapWithZeroMinSpan(inlineView, contentElem);
        assertNotSame(inlineView, wrappedInline);
        assertEquals(0f, wrappedInline.getMinimumSpan(javax.swing.text.View.X_AXIS));

        class SubInlineView extends javax.swing.text.html.InlineView {
            SubInlineView(javax.swing.text.Element e) {
                super(e);
            }
        }
        SubInlineView subInline = new SubInlineView(contentElem);
        javax.swing.text.View preservedSubInline = SwingHtmlUtils.wrapWithZeroMinSpan(subInline, contentElem);
        assertSame(subInline, preservedSubInline);

        javax.swing.text.html.BlockView blockView = new javax.swing.text.html.BlockView(pElem,
                javax.swing.text.View.Y_AXIS);
        javax.swing.text.View wrappedBlock = SwingHtmlUtils.wrapWithZeroMinSpan(blockView, pElem);
        assertNotSame(blockView, wrappedBlock);
        assertEquals(0f, wrappedBlock.getMinimumSpan(javax.swing.text.View.X_AXIS));

        class SubBlockView extends javax.swing.text.html.BlockView {
            SubBlockView(javax.swing.text.Element e) {
                super(e, javax.swing.text.View.Y_AXIS);
            }
        }
        SubBlockView subBlock = new SubBlockView(pElem);
        javax.swing.text.View preservedSubBlock = SwingHtmlUtils.wrapWithZeroMinSpan(subBlock, pElem);
        assertSame(subBlock, preservedSubBlock);

        javax.swing.text.View preservedBodyBlock = SwingHtmlUtils.wrapWithZeroMinSpan(blockView, bodyElem);
        assertSame(blockView, preservedBodyBlock);

        assertNull(SwingHtmlUtils.wrapWithZeroMinSpan(null, contentElem));
    }

    @Test
    void testCreateThumbnailDoesNotUpscaleSmallImage() {
        java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(30, 20,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.image.BufferedImage thumb = UiComponents.createThumbnail(small, 64);
        assertEquals(30, thumb.getWidth());
        assertEquals(20, thumb.getHeight());

        java.awt.image.BufferedImage large = new java.awt.image.BufferedImage(200, 100,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.image.BufferedImage scaled = UiComponents.createThumbnail(large, 50);
        assertEquals(50, scaled.getWidth());
        assertEquals(25, scaled.getHeight());
    }

    private static SwingHtmlUtils.ResponsiveImageView findResponsiveImageView(javax.swing.text.View view) {
        if (view instanceof SwingHtmlUtils.ResponsiveImageView responsive) {
            return responsive;
        }
        int count = view.getViewCount();
        for (int i = 0; i < count; i++) {
            SwingHtmlUtils.ResponsiveImageView found = findResponsiveImageView(view.getView(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}

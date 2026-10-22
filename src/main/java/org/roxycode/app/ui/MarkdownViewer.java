package org.roxycode.app.ui;

import javax.swing.*;

/** A reusable component for viewing Markdown content with theme-aware styling. */
public final class MarkdownViewer extends JTextPane {
    private static final long serialVersionUID = 1L;
    private String currentMarkdown = "";

    public MarkdownViewer() {
        setEditable(false);
        setContentType("text/html");
        setOpaque(true);
        putClientProperty("JEditorPane.honorDisplayProperties", true);

        updateTheme();
        SwingHtmlUtils.installTextContextMenu(this);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (currentMarkdown != null) {
            updateTheme();
        }
    }

    public void updateTheme() {
        SwingHtmlUtils.applyTheme(this, 10);
        render();
    }

    public void setContent(String markdown) {
        this.currentMarkdown = markdown == null ? "" : markdown;
        render();
    }

    private void render() {
        String highlightedHtml = SwingHtmlUtils.renderMarkdownToHtml(currentMarkdown);
        setText("<html><body>" + highlightedHtml + "</body></html>");
        setCaretPosition(0);
    }
}

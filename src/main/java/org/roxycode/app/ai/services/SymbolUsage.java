package org.roxycode.app.ai.services;

import org.apache.commons.lang3.StringUtils;

/** Represents a symbol reference/usage location enriched with enclosing symbol and test file classification. */
public record SymbolUsage(String path, int line, int column, String snippet, String enclosingSymbol, boolean testFile) {

    public SymbolUsage {
        path = StringUtils.defaultString(path);
        snippet = StringUtils.defaultString(snippet);
        enclosingSymbol = StringUtils.defaultString(enclosingSymbol);
    }

    public String getPath() {
        return path;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public String getSnippet() {
        return snippet;
    }

    public String getEnclosingSymbol() {
        return enclosingSymbol;
    }

    public boolean isTestFile() {
        return testFile;
    }
}

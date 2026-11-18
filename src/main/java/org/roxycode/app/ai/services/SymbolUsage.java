package org.roxycode.app.ai.services;

import org.apache.commons.lang3.StringUtils;

/** Represents a symbol reference/usage location enriched with enclosing symbol and test file classification. */
public record SymbolUsage(String path, int line, int column, String snippet, String enclosingSymbol, boolean testFile) {

    public SymbolUsage {
        path = StringUtils.defaultString(path);
        snippet = StringUtils.defaultString(snippet);
        enclosingSymbol = StringUtils.defaultString(enclosingSymbol);
    }
}
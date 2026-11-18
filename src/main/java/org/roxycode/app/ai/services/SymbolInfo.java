package org.roxycode.app.ai.services;

import org.apache.commons.lang3.StringUtils;

/** Structural metadata for a source or test symbol extracted via Universal Ctags. */
public record SymbolInfo(String name, String kind, String path, int startLine, int endLine, String scope,
        String signature) {

    public SymbolInfo {
        name = StringUtils.defaultString(name);
        kind = StringUtils.defaultString(kind);
        path = StringUtils.defaultString(path);
        scope = StringUtils.defaultString(scope);
        signature = StringUtils.defaultString(signature);
    }
}
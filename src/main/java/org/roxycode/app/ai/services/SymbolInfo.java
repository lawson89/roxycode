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

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public String getPath() {
        return path;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public String getScope() {
        return scope;
    }

    public String getSignature() {
        return signature;
    }
}

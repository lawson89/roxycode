package org.roxycode.app.ai.services;

/** Structural metadata for a source or test symbol extracted via Universal Ctags. */
public record SymbolInfo(String name, String kind, String path, int startLine, int endLine, String scope,
        String signature) {

    public SymbolInfo {
        name = name == null ? "" : name;
        kind = kind == null ? "" : kind;
        path = path == null ? "" : path;
        scope = scope == null ? "" : scope;
        signature = signature == null ? "" : signature;
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

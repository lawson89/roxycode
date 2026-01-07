package org.roxycode.core.beans;

public class NamedContent {
    private final String name;
    private final String content;

    public NamedContent(String name, String content) {
        this.name = name;
        this.content = content;
    }

    public String getName() {
        return name;
    }

    public String getContent() {
        return content;
    }
}

package org.roxycode.core.beans;

import java.util.List;

public class ContentBundle {
    private List<NamedContent> files;

    public ContentBundle(List<NamedContent> files) {
        this.files = files;
    }

    public List<NamedContent> getFiles() {
        return files;
    }
}
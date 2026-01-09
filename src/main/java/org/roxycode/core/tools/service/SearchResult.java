package org.roxycode.core.tools.service;

/**
 * Represents a match found during a structural search.
 */
public record SearchResult(String filePath, String className, String elementName, int beginLine, String snippet) {
}

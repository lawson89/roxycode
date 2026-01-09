package org.roxycode.core.tools.service;

/**
 * Represents a match found during a structural search.
 *
 * @param filePath    The path to the file where the match was found, relative to the project root.
 * @param className   The name of the class containing the match.
 * @param elementName The name of the specific element (method, field, etc.) that matched.
 * @param beginLine   The 1-based line number where the match starts.
 * @param snippet     A code snippet illustrating the match.
 */
public record SearchResult(String filePath, String className, String elementName, int beginLine, String snippet) {
}

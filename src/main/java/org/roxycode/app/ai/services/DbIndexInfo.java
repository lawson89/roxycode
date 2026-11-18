package org.roxycode.app.ai.services;

import java.util.List;

/** Metadata for a table index. */
public record DbIndexInfo(String name, boolean unique, List<String> columns) {
}
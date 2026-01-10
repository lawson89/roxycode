package org.roxycode.core.tools.service;

import java.util.List;

/**
 * Represents a skill (agentic workflow) that can be executed by RoxyCode.
 */
public record Skill(String name, String prompt, List<String> steps) {
}

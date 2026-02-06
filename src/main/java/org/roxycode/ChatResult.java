package org.roxycode;

import java.util.List;

public record ChatResult(String text, List<ToolExecution> toolExecutions) {}

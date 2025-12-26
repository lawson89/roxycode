package org.roxycode.core.tools;

import io.micronaut.core.annotation.Introspected;
import java.nio.file.Path;
import java.util.List;

@Introspected
public class ToolDefinition {
    private String description;
    private String type;
    private String source;
    private List<ToolParameter> parameters;

    // Internal tracking of where this tool was loaded from (for relative path resolution)
    private Path definitionLocation;

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public List<ToolParameter> getParameters() { return parameters; }
    public void setParameters(List<ToolParameter> parameters) { this.parameters = parameters; }

    public Path getDefinitionLocation() { return definitionLocation; }
    public void setDefinitionLocation(Path definitionLocation) { this.definitionLocation = definitionLocation; }
}
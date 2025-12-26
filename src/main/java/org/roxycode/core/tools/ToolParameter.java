package org.roxycode.core.tools;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class ToolParameter {
    private String name;
    private String type;
    private String description;
    private boolean required;

    // Getters and Setters needed for Jackson deserialization
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
}
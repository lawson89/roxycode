package org.roxycode.core.tools.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.inject.Singleton;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.tools.ScriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Service for managing and executing agentic skills defined in YAML.
 */
@ScriptService("skillService")
public class SkillService {
    private static final Logger LOG = LoggerFactory.getLogger(SkillService.class);
    private final RoxyProjectService roxyProjectService;
    private final ObjectMapper yamlMapper;

    public SkillService(RoxyProjectService roxyProjectService) {
        this.roxyProjectService = roxyProjectService;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /**
     * Lists all available skills.
     *
     * @return A list of skills.
     */
    public List<Skill> listSkills() {
        List<Skill> skills = new ArrayList<>();
        Path skillsDir = roxyProjectService.getRoxyHome().resolve("skills");
        if (!Files.exists(skillsDir)) {
            return skills;
        }

        try (Stream<Path> paths = Files.walk(skillsDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .forEach(p -> {
                        try {
                            skills.add(yamlMapper.readValue(p.toFile(), Skill.class));
                        } catch (IOException e) {
                            LOG.error("Failed to load skill from {}", p, e);
                        }
                    });
        } catch (IOException e) {
            LOG.error("Failed to list skills", e);
        }
        return skills;
    }

    /**
     * Retrieves a skill by name.
     *
     * @param name The name of the skill.
     * @return The skill, or null if not found.
     */
    public Skill getSkill(String name) {
        return listSkills().stream()
                .filter(s -> s.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
}

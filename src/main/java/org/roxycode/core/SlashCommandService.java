package org.roxycode.core;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.config.GeminiModel;
import org.roxycode.core.config.GeminiModelRegistry;
import org.roxycode.core.tools.service.SkillService;

import java.util.List;
import java.util.stream.Collectors;

@Singleton
public class SlashCommandService {

    private final GenAIService genAIService;
    private final SettingsService settingsService;
    private final GeminiModelRegistry modelRegistry;
    private final RoxyProjectService roxyProjectService;
    private final SkillService skillService;

    @Inject
    public SlashCommandService(GenAIService genAIService, SettingsService settingsService, GeminiModelRegistry modelRegistry, RoxyProjectService roxyProjectService, SkillService skillService) {
        this.genAIService = genAIService;
        this.settingsService = settingsService;
        this.modelRegistry = modelRegistry;
        this.roxyProjectService = roxyProjectService;
        this.skillService = skillService;
    }

    public boolean isCommand(String text) {
        return text != null && text.trim().startsWith("/");
    }

    public CommandResult execute(String text) {
        String trimmed = text.trim();
        String[] parts = trimmed.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (command) {
            case "/clear":
                return new CommandResult(true, "Clearing chat history...", CommandAction.CLEAR);
            case "/reset":
                genAIService.clearHistory();
                return new CommandResult(true, "Session reset. History cleared.", CommandAction.NONE);
            case "/help":
                return new CommandResult(true, getHelpText(), CommandAction.NONE);
            case "/plan":
                roxyProjectService.setCurrentMode(RoxyMode.PLAN);
                return new CommandResult(true, "Switched to PLAN mode.", CommandAction.UPDATE_STATS);
            case "/code":
                roxyProjectService.setCurrentMode(RoxyMode.CODE);
                return new CommandResult(true, "Switched to CODE mode.", CommandAction.UPDATE_STATS);
            case "/model":
                return handleModelCommand(args);
            case "/skill":
                return handleSkillCommand(args);
            default:
                return new CommandResult(false, "Unknown command: " + command, CommandAction.NONE);
        }
    }

    private String getHelpText() {
        return "Available commands:\\n" +
               "*   `/help`: Show this help message.\\n" +
               "*   `/clear`: Clear the chat screen.\\n" +
               "*   `/reset`: Reset the conversation history.\\n" +
               "*   `/model <name>`: Switch to a different Gemini model.\n" +
               "*   `/ask`: Switch to ASK mode.\n" +
               "*   `/plan`: Switch to PLAN mode.\n" +
               "*   `/code`: Switch to CODE mode.\n" +
               "*   `/skill <name>`: Show skill details.";
    }

    private CommandResult handleSkillCommand(String args) {
        if (args.isEmpty()) {
            var skills = skillService.listSkills();
            if (skills.isEmpty()) {
                return new CommandResult(true, "No skills found in ~/.roxy/skills", CommandAction.NONE);
            }
            String names = skills.stream().map(s -> s.name()).collect(Collectors.joining(", "));
            return new CommandResult(true, "Available skills: " + names , CommandAction.NONE);
        }

        if (args.equalsIgnoreCase("off") || args.equalsIgnoreCase("none") || args.equalsIgnoreCase("clear")) {
            return new CommandResult(true, "Skill deactivated.", CommandAction.NONE);
        }

        var skill = skillService.getSkill(args);
        if (skill != null) {
            String details = String.format("### Skill Activated: %s\n\n**Prompt:**\n%s", skill.name(), skill.prompt());
            return new CommandResult(true, details, CommandAction.NONE);
        } else {
            return new CommandResult(false, "Skill not found: " + args, CommandAction.NONE);
        }
    }

    private CommandResult handleModelCommand(String args) {
        if (args.isEmpty()) {
            String models = modelRegistry.getAllModels().stream()
                    .map(GeminiModel::getApiName)
                    .collect(Collectors.joining(", "));
            return new CommandResult(true, "Available models: " + models, CommandAction.NONE);
        }

        var model = modelRegistry.getModelByName(args);
        if (model.isPresent()) {
            settingsService.setGeminiModel(args);
            return new CommandResult(true, "Switched model to: " + args, CommandAction.UPDATE_STATS);
        } else {
            return new CommandResult(false, "Model not found: " + args, CommandAction.NONE);
        }
    }

    public enum CommandAction {
        NONE, CLEAR, UPDATE_STATS
    }

    public record CommandResult(boolean success, String message, CommandAction action) {
    }

    public List<CommandInfo> getAvailableCommands() {
        return List.of(
                new CommandInfo("/help", "Show available commands"),
                new CommandInfo("/clear", "Clear the chat screen"),
                new CommandInfo("/reset", "Reset the conversation history"),
                new CommandInfo("/model", "Switch Gemini model"),
                new CommandInfo("/ask", "Switch to ASK mode"),
                new CommandInfo("/plan", "Switch to PLAN mode"),
                new CommandInfo("/code", "Switch to CODE mode"),
                new CommandInfo("/skill", "Show/list skills")
        );
    }


    public record CommandInfo(String command, String description) {
    }

}

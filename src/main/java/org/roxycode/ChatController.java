package org.roxycode;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.MediaType;
import io.micronaut.views.ModelAndView;
import java.util.Map;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller("/chat")
public class ChatController {

    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);
    private final AgentService agentService;

    public ChatController(AgentService agentService) {
        this.agentService = agentService;
    }

    @Get
    public ModelAndView<Map<String, Object>> index() {
        LOG.info("GET /chat called");
        return new ModelAndView<>("chat", new HashMap<>());
    }

    @Post(consumes = MediaType.APPLICATION_FORM_URLENCODED)
    public ModelAndView<Map<String, Object>> postChat(@Body("prompt") String prompt) {
        LOG.info("POST /chat called with prompt: {}", prompt);
        Map<String, Object> model = new HashMap<>();
        model.put("prompt", prompt);
        try {
            ChatResult result = agentService.chat(prompt);
            LOG.info("AI response received: {}", result.text());
            model.put("response", result.text());
            model.put("toolExecutions", result.toolExecutions());
        } catch (Exception e) {
            LOG.error("Chat error: {}", e.getMessage());
            model.put("error", e.getMessage());
        }
        return new ModelAndView<>("chat", model);
    }
}

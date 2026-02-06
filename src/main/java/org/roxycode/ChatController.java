package org.roxycode;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.MediaType;
import io.micronaut.views.ModelAndView;
import java.util.Map;
import java.util.HashMap;

@Controller("/chat")
public class ChatController {

    private final AgentService agentService;

    public ChatController(AgentService agentService) {
        this.agentService = agentService;
    }

    @Get
    public ModelAndView<Map<String, Object>> index() {
        return new ModelAndView<>("chat", new HashMap<>());
    }

    @Post(consumes = MediaType.APPLICATION_FORM_URLENCODED)
    public ModelAndView<Map<String, Object>> postChat(@Body("prompt") String prompt) {
        Map<String, Object> model = new HashMap<>();
        model.put("prompt", prompt);
        try {
            String response = agentService.chat(prompt);
            model.put("response", response);
        } catch (Exception e) {
            model.put("error", e.getMessage());
        }
        return new ModelAndView<>("chat", model);
    }
}

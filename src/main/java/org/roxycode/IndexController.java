package org.roxycode;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.views.ModelAndView;
import java.util.Map;

@Controller("/")
public class IndexController {

    private final ConfigService configService;

    public IndexController(ConfigService configService) {
        this.configService = configService;
    }

    @Get
    public ModelAndView<Map<String, Object>> index() {
        return new ModelAndView<>("hello", Map.of(
            "message", "Hello World from Pebble!",
            "modelName", configService.getModelName()
        ));
    }
}
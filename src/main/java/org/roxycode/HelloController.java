package org.roxycode;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.views.View;
import java.util.Map;

@Controller("/")
public class HelloController {

    @Get
    @View("hello")
    public Map<String, Object> index() {
        return Map.of("message", "Hello World from dynamic JTE!");
    }
}
package org.roxycode;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.MediaType;
import io.micronaut.views.View;
import java.util.Map;
import java.util.List;
import io.micronaut.http.annotation.Body;
import io.micronaut.serde.annotation.Serdeable;

@Controller("/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @Get
    @View("config")
    public Map<String, Object> index() {
        return Map.of(
            "currentModel", configService.getModelName(),
            "supportedModels", List.of("gemini-3-pro-preview", "gemini-3-flash-preview")
        );
    }

    @Post("/model")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public HttpResponse<?> updateModel(@Body ModelUpdateRequest request) {
        configService.setModelName(request.modelName());
        return HttpResponse.seeOther(java.net.URI.create("/config"));
    }

    @Serdeable
    public record ModelUpdateRequest(String modelName) {}
}

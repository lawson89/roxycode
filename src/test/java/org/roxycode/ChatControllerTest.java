package org.roxycode;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.micronaut.context.annotation.Property;

@MicronautTest
@Property(name = "GEMINI_API_KEY", value = "AIzaSy_fake_key")
class ChatControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void testChatPageLoads() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/chat"), String.class);
        assertEquals(HttpStatus.OK, response.getStatus());
        assertTrue(response.body().contains("AI Chat"));
    }
}

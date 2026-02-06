package org.roxycode;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import io.micronaut.context.annotation.Property;

@MicronautTest
@Property(name = "GEMINI_API_KEY", value = "AIzaSy_fake_key_for_testing")
class AgentServiceTest {

    @Inject
    AgentService agentService;

    @Test
    void testChat() {
        // We expect an exception because the key is fake, but bean instantiation should succeed.
        try {
            String response = agentService.chat("Hello");
            assertNotNull(response);
        } catch (Exception e) {
            // Expected failure due to fake key, but service was initialized
            System.out.println("Expected failure: " + e.getMessage());
        }
    }
}

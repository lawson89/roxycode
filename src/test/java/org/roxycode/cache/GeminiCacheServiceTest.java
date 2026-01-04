package org.roxycode.cache;

import com.google.genai.types.CachedContent;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class GeminiCacheServiceTest {

    @Inject
    GeminiCacheService geminiCacheService;

    @Test
    void testListCaches() throws IOException, InterruptedException {
        assertNotNull(geminiCacheService);
    }
}

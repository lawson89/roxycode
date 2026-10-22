package org.roxycode.app.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.Test;

public class GeminiModelsTest {

    @Test
    public void testTomlParsing() throws Exception {
        String toml =
                """
                [[models]]
                apiName = "test-id"
                name = "Test Name"
                description = "Test Desc"
                """;
        TomlMapper mapper = new TomlMapper();
        GeminiModels models = mapper.readValue(toml, GeminiModels.class);

        assertNotNull(models);
        assertEquals(1, models.models().size());
        assertEquals("test-id", models.models().get(0).apiName());
        assertEquals("Test Name", models.models().get(0).name());
        assertEquals("Test Desc", models.models().get(0).description());
    }
}

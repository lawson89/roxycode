package org.roxycode.app.model.config;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

public class GeminiModelsTest {

    @Test
    public void testTomlParsing() throws Exception {
        String toml = "[[models]]\napiName = \"test-id\"\nname = \"Test Name\"\ndescription = \"Test Desc\"";
        TomlMapper mapper = new TomlMapper();
        GeminiModels models = mapper.readValue(toml, GeminiModels.class);
        
        assertNotNull(models);
        assertEquals(1, models.models().size());
        assertEquals("test-id", models.models().get(0).apiName());
        assertEquals("Test Name", models.models().get(0).name());
        assertEquals("Test Desc", models.models().get(0).description());
    }
}
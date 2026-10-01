package org.roxycode.app.ai.services.discovery;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DiscoverySessionTest {
    @Test
    void testDiscoverySessionImmutability() {
        List<String> needs = List.of("Need 1");
        DiscoverySession session = new DiscoverySession("Feature", needs, List.of(), false);
        assertEquals(1, session.userNeeds().size());
        assertThrows(UnsupportedOperationException.class, () -> session.userNeeds().add("Need 2"));
    }
}

package org.roxycode.app.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoverySessionTest {
  @Test
  void testDiscoverySessionImmutability() {
    List<String> needs = List.of("Need 1");
    DiscoverySession session = new DiscoverySession("Feature", needs, List.of(), false);
    assertEquals(1, session.userNeeds().size());
    assertThrows(UnsupportedOperationException.class, () -> session.userNeeds().add("Need 2"));
  }
}

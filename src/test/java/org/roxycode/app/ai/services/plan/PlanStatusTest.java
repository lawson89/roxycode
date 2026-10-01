package org.roxycode.app.ai.services.plan;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PlanStatusTest {

    @Test
    public void testEnumStates() {
        assertNotNull(PlanStatus.PLAN);
        assertNotNull(PlanStatus.CODE);
        assertNotNull(PlanStatus.REVIEW);
        assertNotNull(PlanStatus.COMPLETE);
    }

    @Test
    public void testGetDirName() {
        assertEquals("plan", PlanStatus.PLAN.getDirName());
        assertEquals("code", PlanStatus.CODE.getDirName());
        assertEquals("review", PlanStatus.REVIEW.getDirName());
        assertEquals("complete", PlanStatus.COMPLETE.getDirName());
    }

    @Test
    public void testFromString() {
        assertEquals(PlanStatus.PLAN, PlanStatus.fromString("PLAN"));
        assertEquals(PlanStatus.PLAN, PlanStatus.fromString("plan"));
        assertEquals(PlanStatus.REVIEW, PlanStatus.fromString("REVIEW"));
        assertNull(PlanStatus.fromString(null));
    }

    @Test
    public void testFromStringInvalid() {
        assertThrows(IllegalArgumentException.class, () -> {
            PlanStatus.fromString("INVALID");
        });
    }
}
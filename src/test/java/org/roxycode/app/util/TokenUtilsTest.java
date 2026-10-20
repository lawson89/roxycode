package org.roxycode.app.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TokenUtilsTest {

    @Test
    void testEstimateTokens() {
        assertEquals(0L, TokenUtils.estimateTokens(null));
        assertEquals(0L, TokenUtils.estimateTokens(""));
        assertEquals(0L, TokenUtils.estimateTokens("abc"));
        assertEquals(1L, TokenUtils.estimateTokens("abcd"));
        assertEquals(3L, TokenUtils.estimateTokens("stable prompt"));
    }
}

package com.byteskeptical.credcat.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Checks} value predicates and normalizer.
 */
class ChecksTest {

    @DisplayName("isNullOrBlank: null, empty, and whitespace count as blank")
    @Test
    void isNullOrBlank_treatsWhitespaceAsBlank() {
        assertTrue(Checks.isNullOrBlank(null));
        assertTrue(Checks.isNullOrBlank(""));
        assertTrue(Checks.isNullOrBlank("   "));
        assertFalse(Checks.isNullOrBlank("x"));
    }

    @DisplayName("isNullOrEmpty: null and empty collections")
    @Test
    void isNullOrEmpty_handlesNullAndEmpty() {
        assertTrue(Checks.isNullOrEmpty(null));
        assertTrue(Checks.isNullOrEmpty(Collections.emptyList()));
        assertFalse(Checks.isNullOrEmpty(List.of("x")));
    }

    @DisplayName("trimToNull: trims, nulls out blanks")
    @Test
    void trimToNull_normalizes() {
        assertNull(Checks.trimToNull(null));
        assertNull(Checks.trimToNull("   "));
        assertEquals("x", Checks.trimToNull("  x  "));
    }

    @DisplayName("isBase64: accepts valid base64, rejects junk")
    @Test
    void isBase64_validatesEncoding() {
        assertTrue(Checks.isBase64("YWJj"));
        assertFalse(Checks.isBase64("not base64!"));
        assertFalse(Checks.isBase64(""));
        assertFalse(Checks.isBase64(null));
    }

    @DisplayName("isJson: accepts valid JSON, rejects junk")
    @Test
    void isJson_validatesJson() {
        assertTrue(Checks.isJson("{\"a\":1}"));
        assertTrue(Checks.isJson("[1,2,3]"));
        assertFalse(Checks.isJson("{bad"));
        assertFalse(Checks.isJson(""));
        assertFalse(Checks.isJson(null));
    }
}

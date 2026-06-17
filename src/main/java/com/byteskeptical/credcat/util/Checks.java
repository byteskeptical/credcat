package com.byteskeptical.credcat.util;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Base64;
import java.util.Collection;

/**
 * Null-tolerant value checks and normalizations.
 *
 * <ul>
 *   <li>{@link #isBase64(String)} valid standard base64?</li>
 *   <li>{@link #isJson(String)} valid JSON?</li>
 *   <li>{@link #isNullOrBlank(String)} treats whitespace as missing. The
 *       right default for config values, request fields, identifiers.</li>
 *   <li>{@link #isNullOrEmpty(Collection)} the parallel emptiness check
 *       for any {@link Collection}; collections have no {@code isBlank()}
 *       equivalent.</li>
 *   <li>{@link #trimToNull(String)} both checks and returns the cleaned
 *       value in one pass. The right tool for property reads from
 *       hand-edited files where trailing whitespace is a real risk.</li>
 * </ul>
 *
 * <p>The {@code is*} methods are pure predicates. {@code trimToNull} is the
 * one that produces a cleaned value.</p>
 */
public final class Checks {

    private Checks() {}

    /**
     * Validates a string as standard base64, surrounding whitespace is
     * tolerated.
     *
     * @param s The string to check.
     * @return {@code true} if {@code s} decodes cleanly as base64.
     */
    public static boolean isBase64(String s) {
        if (s == null) {
            return false;
        }

        String t = s.trim();

        if (t.isEmpty()) {
            return false;
        }

        try {
            Base64.getDecoder().decode(t);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Validates a string as parseable JSON. Defers entirely to Jackson.
     * Accepts any well-formed JSON value (object, array, scalar, etc.).
     *
     * @param s The string to check.
     * @return {@code true} if {@code s} is well-formed JSON.
     */
    public static boolean isJson(String s) {
        if (s == null) {
            return false;
        }

        try {
            JsonNode node = JsonHandler.getObjectMapper().readTree(s);
            return node != null && !node.isMissingNode();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Whitespace-tolerant emptiness check. "Missing", "empty", and
     * "whitespace-only" all collapse to {@code true}.
     *
     * @param s The string to check.
     * @return {@code true} if {@code s} is {@code null}, zero-length, or
     *         whitespace only.
     */
    public static boolean isNullOrBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Null-tolerant emptiness check for any {@link Collection}. Saves the
     * {@code c == null || c.isEmpty()} pair from being inlined at every
     * call site that handles an optional list, set, or map values view.
     *
     * @param c The collection to check; may be {@code null}.
     * @return {@code true} if {@code c} is {@code null} or has zero elements.
     */
    public static boolean isNullOrEmpty(Collection<?> c) {
        return c == null || c.isEmpty();
    }

    /**
     * Normalizes raw input into one of two states: {@code null} (for
     * missing/empty/whitespace) or the trimmed value. Downstream code only
     * has to null-check rather than worry about empty strings or stray
     * whitespace.
     *
     * <p>{@link #isNullOrBlank} can't replace this: it only tells you whether
     * the string is missing; it doesn't give you back the cleaned value.</p>
     *
     * @param s The string to normalize.
     * @return The trimmed value, or {@code null} if the input was null, empty,
     *         or whitespace only.
     */
    public static String trimToNull(String s) {
        if (s == null) {
            return null;
        }

        String t = s.trim();

        return t.isEmpty() ? null : t;
    }
}

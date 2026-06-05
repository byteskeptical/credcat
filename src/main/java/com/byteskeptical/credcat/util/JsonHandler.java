package com.byteskeptical.credcat.util;
 
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

/**
 * The (Serial) Transporter aka JSON Statham.
 */
public class JsonHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** No instance, no problem. */
    private JsonHandler() {}

    /**
     * Exposes the shared, pre-configured ObjectMapper for callers that need
     * Jackson's streaming or tree APIs directly.
     *
     * @return The shared, thread-safe ObjectMapper instance.
     */
    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }

    /**
     * Serializes an object to pretty-printed JSON.
     *
     * @param obj The object to serialize.
     * @return The JSON string representation.
     * @throws JsonProcessingException if {@code obj} cannot be processed as JSON.
     */
    public static String toJson(Object obj) throws JsonProcessingException {
        return OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(obj);
    }

    /**
     * Streams an object as JSON straight to an output stream, sidestepping
     * the intermediate String allocation that {@link #toJson(Object)} requires.
     *
     * @param obj The object to serialize.
     * @param out The destination stream. Not closed by this method.
     * @throws IOException if writing fails.
     */
    public static void writeJson(Object obj, OutputStream out) throws IOException {
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, obj);
    }

    /**
     * Builds a single-key JSON object as a string. For small status envelopes
     * (errors, version banners, etc.) where the caller would rather not deal
     * with a checked exception for input that's trivially serializable.
     *
     * @param key   The JSON property name. Must not be {@code null}.
     * @param value The value. {@code null} is rendered as an empty string so
     *              the output is always valid, parseable JSON.
     * @return A JSON object like {@code {"key" : "value"}}.
     */
    public static String envelope(String key, String value) {
        try {
            return toJson(Map.of(key, value != null ? value : ""));
        } catch (JsonProcessingException e) {
            // A two-string Map can't actually fail. Fall back to a parseable
            // empty object if it somehow does.
            return "{}";
        }
    }

    /**
     * Deserializes a JSON string into a Java object of a specified class.
     *
     * @param <T>   The type of the object to deserialize to.
     * @param json  The JSON string to deserialize.
     * @param clazz The class of the object to return.
     * @return An object of type T populated from the JSON.
     * @throws JsonProcessingException if the JSON is malformed.
     */
    public static <T> T fromJson(String json, Class<T> clazz) throws JsonProcessingException {
        return OBJECT_MAPPER.readValue(json, clazz);
    }
}

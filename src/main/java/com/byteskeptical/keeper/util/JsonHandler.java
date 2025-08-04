package com.byteskeptical.keeper.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The (Serial) Transporter aka JSON Statham.
 */
public class JsonHandler {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Converts a Java object to a pretty-printed JSON string.
     *
     * @param obj The object to serialize to JSON.
     * @return The JSON string representation of the object.
     * @throws JsonProcessingException if the object cannot be processed as JSON.
     */
    public static String toJson(Object obj) throws JsonProcessingException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }

    /**
     * Deserializes a JSON string into a Java object of a specified class.
     *
     * @param <T> The type of the object to deserialize to.
     * @param json The JSON string to deserialize.
     * @param clazz The class of the object to return.
     * @return An object of type T populated with data from the JSON string.
     * @throws JsonProcessingException if the JSON string is malformed or cannot be processed.
     */
    public static <T> T fromJson(String json, Class<T> clazz) throws JsonProcessingException {
        return objectMapper.readValue(json, clazz);
    }
}

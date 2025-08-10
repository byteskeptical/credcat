package com.byteskeptical.credcat.model;

import java.util.List;
import java.util.Map;

/**
 * Record(s) fields format.
 */
public class SecretResponse {

    /**
     * Minimal file structure.
     */
    public static class FileInfo {
        private String name;
        private String path;

        /**
         * Constructs a new FileInfo object.
         *
         * @param name The name of the file.
         * @param path The path to the file on the filesystem.
         */
        public FileInfo(String name, String path) {
            this.name = name;
            this.path = path;
        }

        /**
         * Retrieves the name of the file.
         *
         * @return The file name.
         */
        public String getName() {
            return name;
        }

        /**
         * Retrieves the path to the file.
         *
         * @return The file path.
         */
        public String getPath() {
            return path;
        }
    }
}

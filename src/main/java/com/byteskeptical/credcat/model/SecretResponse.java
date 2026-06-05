package com.byteskeptical.credcat.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Record(s) fields format.
 */
public class SecretResponse {

    /**
     * File structure. Carries a path (DISK), base64 content (INLINE), or
     * neither (NONE). {@code @JsonInclude} drops whichever isn't set.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FileInfo {
        private final String content;
        private final String mimeType;
        private final String name;
        private final String path;
        private final Long size;

        /**
         * Constructs a new FileInfo object.
         *
         * @param name     The name of the file.
         * @param path     Filesystem path to the saved file, or {@code null}.
         * @param content  Base64-encoded file content, or {@code null}.
         * @param mimeType The MIME type, or {@code null} if unknown.
         * @param size     File size in bytes, or {@code null} if unknown.
         */
        public FileInfo(String name, String path, String content,
                        String mimeType, Long size) {
            this.content = content;
            this.mimeType = mimeType;
            this.name = name;
            this.path = path;
            this.size = size;
        }

        /**
         * Builds a FileInfo for a file written to local disk.
         *
         * @param name     The name of the file.
         * @param path     The path to the saved file.
         * @param mimeType The MIME type, or {@code null} if unknown.
         * @param size     File size in bytes, or {@code null} if unknown.
         * @return A disk-backed FileInfo.
         */
        public static FileInfo forDisk(String name, String path,
                                       String mimeType, Long size) {
            return new FileInfo(name, path, null, mimeType, size);
        }

        /**
         * Builds a FileInfo carrying base64 content inline.
         *
         * @param name     The name of the file.
         * @param content  Base64-encoded file content.
         * @param mimeType The MIME type, or {@code null} if unknown.
         * @param size     File size in bytes, or {@code null} if unknown.
         * @return An inline FileInfo.
         */
        public static FileInfo forInline(String name, String content,
                                         String mimeType, Long size) {
            return new FileInfo(name, null, content, mimeType, size);
        }

        /**
         * Builds a metadata-only FileInfo, no path, no payload.
         *
         * @param name     The name of the file.
         * @param mimeType The MIME type, or {@code null} if unknown.
         * @param size     File size in bytes, or {@code null} if unknown.
         * @return A metadata-only FileInfo.
         */
        public static FileInfo skipped(String name, String mimeType, Long size) {
            return new FileInfo(name, null, null, mimeType, size);
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
         * @return The file path, or {@code null} if not written to disk.
         */
        public String getPath() {
            return path;
        }

        /**
         * Retrieves the base64-encoded file content.
         *
         * @return The content, or {@code null} for non-inline transports.
         */
        public String getContent() {
            return content;
        }

        /**
         * Retrieves the MIME type of the file.
         *
         * @return The MIME type, or {@code null} if unknown.
         */
        public String getMimeType() {
            return mimeType;
        }

        /**
         * Retrieves the size of the file.
         *
         * @return File size in bytes, or {@code null} if unknown.
         */
        public Long getSize() {
            return size;
        }
    }
}

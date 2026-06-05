package com.byteskeptical.credcat.file;

import com.byteskeptical.credcat.util.Checks;
import java.util.Locale;

/**
 * How the service should hand attached files back to the caller.
 *
 * <p>Selectable per-request, falls back to a service-wide default. The correct
 * answer depends on the deployment:</p>
 *
 * <ul>
 *   <li>{@link #DISK} -- Write files to local storage and return their paths.
 *       Best when the consumer runs on the same host or shared filesystem.</li>
 *   <li>{@link #INLINE} -- Base64-encode the file content into the JSON
 *       response. The only viable option in sandboxed environments where the
 *       local filesystem is unwritable or ephemeral.</li>
 *   <li>{@link #NONE} -- Skip downloads entirely and return metadata only.
 *       Cheapest path when the caller just needs field values.</li>
 * </ul>
 */
public enum FileTransport {
    /** Write files to local storage and return their paths. */
    DISK,
    /** Base64-encode the file content directly into the JSON response. */
    INLINE,
    /** Skip downloads; return file metadata only. */
    NONE;

    /**
     * Lenient parser for the files configuration value. Ignores case, falls
     * back to the supplied default when the input is null, blank, or
     * unrecognized.
     *
     * @param value    The textual value to parse. May be {@code null}.
     * @param fallback The value to use when {@code value} cannot be parsed.
     * @return A non-null FileTransport.
     */
    public static FileTransport parse(String value, FileTransport fallback) {
        String trimmed = Checks.trimToNull(value);

        if (trimmed == null) {
            return fallback;
        }

        try {
            return FileTransport.valueOf(trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}

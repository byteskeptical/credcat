package com.byteskeptical.credcat.file;

import com.byteskeptical.credcat.model.SecretResponse;
import com.byteskeptical.credcat.util.Checks;
import com.keepersecurity.secretsManager.core.KeeperFile;
import com.keepersecurity.secretsManager.core.KeeperFileData;
import com.keepersecurity.secretsManager.core.SecretsManager;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Materializing a KeeperFile.
 */
public interface FileHandler {

    /** Shared logger for the file handlers. */
    Logger LOGGER = Logger.getLogger(FileHandler.class.getName());

    /**
     * Downloads or skips a single attachment and produces the FileInfo entry
     * for inclusion in the JSON response.
     *
     * @param file The KeeperFile to process. Never {@code null}.
     * @return A non-null FileInfo describing the result.
     * @throws IOException if the underlying download or persistence fails.
     */
    SecretResponse.FileInfo handle(KeeperFile file) throws IOException;

    /**
     * Factory for transport handling and an optional save location.
     *
     * @param transport    Which transport, pick one. Must not be {@code null}.
     * @param saveLocation The directory to write files into when DISK is used.
     *                     Ignored otherwise.
     * @return An initialized handler.
     * @throws IOException if DISK is selected and the directory cannot be created.
     */
    static FileHandler forTransport(FileTransport transport, String saveLocation)
            throws IOException {
        Objects.requireNonNull(transport, "transport");

        switch (transport) {
            case DISK:
                return new Disk(saveLocation);
            case INLINE:
                return new Inline();
            case NONE:
                return new None();
            default:
                throw new IllegalStateException("Unhandled transport: " + transport);
        }
    }

    /**
     * Pulls the metadata trifecta (name, mime, size) out of a KeeperFile,
     * tolerating the case where {@code KeeperFile.getData()} returns
     * {@code null}.
     *
     * @param file The KeeperFile to inspect.
     * @return KeeperFile metadata; never {@code null}.
     */
    static Metadata metadataFor(KeeperFile file) {
        KeeperFileData data = file.getData();

        if (data == null) {
            return new Metadata(file.getFileUid(), null, null);
        }

        return new Metadata(data.getName(), data.getType(), data.getSize());
    }

    /**
     * Value holder for the {@link KeeperFileData} bits used by the
     * handlers. Built via {@link FileHandler#metadataFor(KeeperFile)}.
     */
    final class Metadata {
        final String mime;
        final String name;
        final Long size;

        Metadata(String name, String mime, Long size) {
            this.mime = mime;
            this.name = name;
            this.size = size;
        }
    }

    /**
     * Writes a file to local disk and returns its path. Buffered so we don't
     * issue a syscall per byte. The Keeper SDK exposes {@code downloadFile}
     * only as {@code byte[]}. Streaming isn't possible from source, we still
     * want to avoid two copies in memory and use a buffered write for syscall
     * amortization.
     */
    final class Disk implements FileHandler {
        private static final int BUFFER_SIZE = 64 * 1024;

        private final Path downloadDir;

        Disk(String saveLocation) throws IOException {
            if (Checks.isNullOrBlank(saveLocation)) {
                throw new IOException("DISK transport requires a save location.");
            }

            this.downloadDir = Path.of(saveLocation);
            Files.createDirectories(this.downloadDir);
        }

        @Override
        public SecretResponse.FileInfo handle(KeeperFile file) throws IOException {
            byte[] bytes = SecretsManager.downloadFile(file);

            Metadata m = metadataFor(file);
            Path target = downloadDir.resolve(sanitize(m.name));

            if (Files.exists(target)) {
                target = downloadDir.resolve(
                        UUID.randomUUID() + "_" + target.getFileName());
            }

            try (OutputStream os = new BufferedOutputStream(
                    Files.newOutputStream(target), BUFFER_SIZE)) {
                os.write(bytes);
            }

            LOGGER.log(Level.INFO,
                    "Saved attachment ''{0}'' ({1} bytes) to {2}",
                    new Object[] { m.name, bytes.length, target });

            return SecretResponse.FileInfo.forDisk(
                    m.name, target.toString(), m.mime, (long) bytes.length);
        }

        /**
         * Keeps an attachment name inside the download directory: separators
         * become underscores, and the bare dot names ({@code "."},
         * {@code ".."}) that would resolve to the directory itself or its
         * parent fall back to {@code "unnamed"}.
         */
        private static String sanitize(String name) {
            if (Checks.isNullOrBlank(name)) {
                return "unnamed";
            }

            String safe = name.replace('/', '_').replace('\\', '_');
 
            return ".".equals(safe) || "..".equals(safe) ? "unnamed" : safe;
        }
    }

    /**
     * Holds the file's bytes in memory and returns base64-encoded content.
     * The only transport that survives a read-only filesystem.
     */
    final class Inline implements FileHandler {

        @Override
        public SecretResponse.FileInfo handle(KeeperFile file) throws IOException {
            byte[] bytes = SecretsManager.downloadFile(file);

            Metadata m = metadataFor(file);
            String encoded = Base64.getEncoder().encodeToString(bytes);

            LOGGER.log(Level.INFO,
                    "Inlined attachment ''{0}'' ({1} bytes)",
                    new Object[] { m.name, bytes.length });

            return SecretResponse.FileInfo.forInline(
                    m.name, encoded, m.mime, (long) bytes.length);
        }
    }

    /**
     * Metadata only. No download is performed, nothing leaves Keeper.
     */
    final class None implements FileHandler {

        @Override
        public SecretResponse.FileInfo handle(KeeperFile file) {
            Metadata m = metadataFor(file);
            return SecretResponse.FileInfo.skipped(m.name, m.mime, m.size);
        }
    }
}

package com.byteskeptical.credcat.config;

import com.byteskeptical.credcat.util.Checks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Resolves the KSM device config from a request value, a named lookup,
 * or the service-wide default in that order.
 *
 * <ol>
 *   <li>An explicit literal in the request body ({@code config}), treated as
 *       either a filesystem path or raw base64/JSON content.</li>
 *   <li>A named config in the request body ({@code configName}), looked up
 *       against the configured directory and environment variable prefix
 *       (in that order).</li>
 *   <li>The service default ({@code keeper.config} from properties).</li>
 * </ol>
 *
 * <p>This class never writes to disk and never logs the resolved contents.</p>
 */
public class KeeperConfig {

    private static final Logger LOGGER = Logger.getLogger(KeeperConfig.class.getName());

    /** Filename extensions tried during named lookup. */
    private static final List<String> EXTENSIONS = List.of(".json", ".b64", "");

    private final String configContent;
    private final Path configDir;
    private final String envPrefix;

    /**
     * Builds a resolver over an optional inline default, a named config
     * directory, and an env var prefix.
     *
     * @param configContent The resolved {@code keeper.config} payload, or
     *                      {@code null} to disable the default.
     * @param configDir     Directory holding named configs, or {@code null}.
     * @param envPrefix     Env var prefix for named configs
     *                      (e.g. {@code "KEEPER_CONFIG_"}), or {@code null}.
     */
    public KeeperConfig(String configContent, Path configDir, String envPrefix) {
        this.configContent = configContent;
        this.configDir = configDir;
        this.envPrefix = envPrefix;
    }

    /**
     * A resolved KSM device config along with where it came from.
     */
    public static final class KSM {
        private final String content;
        private final String origin;

        KSM(String content, String origin) {
            this.content = content;
            this.origin = origin;
        }

        /**
         * Retrieve the resolved config payload.
         *
         * @return The config payload (JSON or base64). Never {@code null}.
         */
        public String getContent() {
            return content;
        }

        /**
         * Where did thow config come from?.
         *
         * @return Absolute path for file sourced configs, env var name for
         *         env sourced configs, or {@code null} for request literals
         *         and the inline default.
         */
        public String getOrigin() {
            return origin;
        }
    }

    /**
     * Resolves the config for an incoming request.
     *
     * @param config     Path, JSON, or base64; may be {@code null}.
     * @param configName Symbolic name to look up; may be {@code null}.
     * @return The resolved config, or {@code null} if nothing matched.
     */
    public KSM resolve(String config, String configName) {
        if (!Checks.isNullOrBlank(config)) {
            return interpret(config);
        }

        if (!Checks.isNullOrBlank(configName)) {
            KSM named = configByName(configName);
            if (named != null) {
                return named;
            }
            LOGGER.log(Level.WARNING, "Named config ''{0}'' not found.", configName);
        }

        if (!Checks.isNullOrBlank(configContent)) {
            return new KSM(configContent, null);
        }

        return null;
    }

    /**
     * Interprets a raw value as a filesystem path, JSON literal, or base64.
     *
     * @param raw The value to interpret.
     * @return The resolved config. Never {@code null}.
     * @throws IllegalArgumentException if {@code raw} is none of the three
     *         or names an existing file that cannot be read.
     */
    public KSM interpret(String raw) {
        Objects.requireNonNull(raw, "raw");
        Path path = null;

        // Multi-line or very long strings can't plausibly be paths, skip
        if (raw.indexOf('\n') < 0 && raw.indexOf('\r') < 0 && raw.length() <= 4096) {
            try {
                path = Path.of(raw);
            } catch (InvalidPathException ignored) {
                // not a usable path; fall through to literal parsing
            }
        }

        if (path != null && Files.isRegularFile(path)) {
            try {
                return new KSM(
                        Files.readString(path, StandardCharsets.UTF_8),
                        path.toString());
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to read Keeper config file.", e);
                throw new IllegalArgumentException(
                        "config points at " + path + " but reading it failed: "
                        + e.getMessage(), e);
            }
        }

        if (Checks.isJson(raw) || Checks.isBase64(raw)) {
            return new KSM(raw, null);
        }

        throw new IllegalArgumentException(
                "config is neither a readable file path, JSON, nor base64");
    }

    /**
     * Looks up a config by symbolic name in the configured directory and env
     * vars, in that order.
     */
    private KSM configByName(String name) {
        String safe = name.replaceAll("[^\\w.-]", "");

        if (Checks.isNullOrBlank(safe)) {
            LOGGER.log(Level.WARNING, "Rejected unsafe config name: ''{0}''", name);
            return null;
        }

        if (configDir != null) {
            for (String ext : EXTENSIONS) {
                Path candidate = configDir.resolve(safe + ext);

                if (Files.isRegularFile(candidate)) {
                    try {
                        return new KSM(
                                Files.readString(candidate, StandardCharsets.UTF_8),
                                candidate.toString());
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING,
                                "Failed to read named config from " + candidate, e);
                    }
                }
            }
        }

        if (!Checks.isNullOrBlank(envPrefix)) {
            String envName = envPrefix + safe.toUpperCase(Locale.ROOT);
            String value = Checks.trimToNull(System.getenv(envName));

            if (value != null) {
                return new KSM(value, envName);
            }
        }

        return null;
    }

    /**
     * Every named config visible across all sources.
     *
     * @return Sorted list of named configs visible across all sources.
     */
    public List<String> listConfigs() {
        Set<String> names = new TreeSet<>();

        if (configDir != null && Files.isDirectory(configDir)) {
            try (Stream<Path> entries = Files.list(configDir)) {
                entries.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .map(KeeperConfig::stripKnownExt)
                        .filter(s -> !Checks.isNullOrBlank(s))
                        .forEach(names::add);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to list config directory.", e);
            }
        }

        if (!Checks.isNullOrBlank(envPrefix)) {
            for (String key : System.getenv().keySet()) {
                if (key.startsWith(envPrefix)) {
                    names.add(key.substring(envPrefix.length()).toLowerCase(Locale.ROOT));
                }
            }
        }

        return new ArrayList<>(names);
    }

    private static String stripKnownExt(String filename) {
        for (String ext : EXTENSIONS) {
            if (!Checks.isNullOrBlank(ext) && filename.endsWith(ext)) {
                return filename.substring(0, filename.length() - ext.length());
            }
        }

        return filename;
    }
}

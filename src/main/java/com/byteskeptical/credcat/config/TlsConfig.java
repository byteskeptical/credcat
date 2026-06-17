package com.byteskeptical.credcat.config;

import com.byteskeptical.credcat.util.Checks;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/**
 * Optional TLS for the embedded server, resolved from the
 * {@code server.tls.*} properties.
 *
 * <p>Disabled by default. When {@code server.tls.enabled=true} the server
 * identity is read from a keystore ({@code server.tls.keystore}) and the
 * keystore password is resolved in two steps:</p>
 *
 * <ol>
 *   <li>{@code server.tls.keystore_password}: a literal in the properties
 *       file. Convenient for development; avoid in production.</li>
 *   <li>{@code server.tls.keystore_password_env}: the <em>name</em> of an
 *       environment variable holding the password. The recommended option.</li>
 * </ol>
 *
 * <p>Mutual TLS is available via {@code server.tls.client_auth} (NONE, WANT,
 * NEED) with an optional dedicated truststore; without one, the JVM's default
 * trust anchors are used. Enabled protocols can be pinned with
 * {@code server.tls.protocols}.</p>
 *
 * <p>Construction only parses; the keystore is opened by
 * {@link #createConfigurator()} so a bad TLS setup fails loudly at server
 * startup rather than on the first request. This class never logs key
 * material or passwords.</p>
 */
public final class TlsConfig {

    private static final Logger LOGGER = Logger.getLogger(TlsConfig.class.getName());

    /** The instance handed out when TLS is disabled. */
    private static final TlsConfig DISABLED = new TlsConfig(
            false, ClientAuth.NONE, null, null, null, List.of(), null, null, null);

    private final boolean enabled;
    private final ClientAuth clientAuth;
    private final char[] keystorePassword;
    private final List<String> protocols;
    private final Path keystorePath;
    private final Path truststorePath;
    private final char[] truststorePassword;
    private final String keystoreType;
    private final String truststoreType;

    /**
     * How the server treats client certificates during the handshake.
     */
    public enum ClientAuth {
        /** Never request a client certificate. The default. */
        NONE,
        /** Request a certificate; proceed without one. */
        WANT,
        /** Require a certificate; refuse the handshake without one. */
        NEED;

        /**
         * Parser for the client auth configuration value. Ignores case,
         * falls back to {@link #NONE} when the input is null, blank, or
         * unrecognized.
         *
         * @param value The textual value to parse. May be {@code null}.
         * @return A non-null ClientAuth.
         */
        static ClientAuth parse(String value) {
            String trimmed = Checks.trimToNull(value);

            if (trimmed == null) {
                return NONE;
            }

            try {
                return ClientAuth.valueOf(trimmed.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                LOGGER.log(Level.WARNING,
                        "Unrecognized server.tls.client_auth ''{0}''; using NONE.",
                        trimmed);
                return NONE;
            }
        }
    }

    private TlsConfig(boolean enabled, ClientAuth clientAuth,
                      Path keystorePath, String keystoreType, char[] keystorePassword,
                      List<String> protocols,
                      Path truststorePath, String truststoreType,
                      char[] truststorePassword) {
        this.clientAuth = clientAuth;
        this.enabled = enabled;
        this.keystorePassword = keystorePassword;
        this.keystorePath = keystorePath;
        this.keystoreType = keystoreType;
        this.protocols = protocols;
        this.truststorePassword = truststorePassword;
        this.truststorePath = truststorePath;
        this.truststoreType = truststoreType;
    }

    /**
     * Builds a TlsConfig from the {@code server.tls.*} properties.
     *
     * @param props The loaded application properties.
     * @return An enabled TlsConfig, or the disabled instance when
     *         {@code server.tls.enabled} is unset or false.
     * @throws IllegalArgumentException if TLS is enabled but the keystore
     *         path or its password cannot be resolved.
     */
    public static TlsConfig fromProperties(Properties props) {
        boolean enabled = Boolean.parseBoolean(
                props.getProperty("server.tls.enabled", "false"));

        if (!enabled) {
            return DISABLED;
        }

        String keystoreProp = Checks.trimToNull(
                props.getProperty("server.tls.keystore"));
        if (keystoreProp == null) {
            throw new IllegalArgumentException(
                    "server.tls.enabled is true but server.tls.keystore is not set.");
        }

        char[] keystorePassword = resolvePassword(props,
                "server.tls.keystore_password",
                "server.tls.keystore_password_env");
        if (keystorePassword == null) {
            throw new IllegalArgumentException(
                    "server.tls.enabled is true but no keystore password was "
                    + "resolved. Set server.tls.keystore_password or point "
                    + "server.tls.keystore_password_env at an environment "
                    + "variable holding it.");
        }

        String protocolsProp = Checks.trimToNull(
                props.getProperty("server.tls.protocols"));
        List<String> protocols = protocolsProp == null
                ? List.of()
                : Arrays.stream(protocolsProp.split(","))
                        .map(Checks::trimToNull)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toUnmodifiableList());

        String truststoreProp = Checks.trimToNull(
                props.getProperty("server.tls.truststore"));

        return new TlsConfig(
                true,
                ClientAuth.parse(props.getProperty("server.tls.client_auth")),
                Path.of(keystoreProp),
                props.getProperty("server.tls.keystore_type", "PKCS12"),
                keystorePassword,
                protocols,
                truststoreProp != null ? Path.of(truststoreProp) : null,
                props.getProperty("server.tls.truststore_type", "PKCS12"),
                resolvePassword(props,
                        "server.tls.truststore_password",
                        "server.tls.truststore_password_env"));
    }

    /**
     * Is TLS on for this deployment?.
     *
     * @return {@code true} when the server should listen for HTTPS.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Opens the keystore (and truststore, when configured), builds the
     * SSLContext, and wraps it in a configurator that applies the protocol
     * pinning and client auth policy to every handshake.
     *
     * @return A configurator ready for {@code HttpsServer.setHttpsConfigurator}.
     * @throws IOException if a store file cannot be read.
     * @throws GeneralSecurityException if a store cannot be opened with the
     *         resolved password or the SSLContext cannot be initialized.
     * @throws IllegalStateException if invoked while TLS is disabled.
     */
    public HttpsConfigurator createConfigurator()
            throws IOException, GeneralSecurityException {
        if (!enabled) {
            throw new IllegalStateException("TLS is not enabled.");
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        try {
            KeyStore keystore = loadStore(
                    keystorePath, keystoreType, keystorePassword);
            kmf.init(keystore, keystorePassword);
        } finally {
            Arrays.fill(keystorePassword, '\0');
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        if (truststorePath != null) {
            try {
                tmf.init(loadStore(
                        truststorePath, truststoreType, truststorePassword));
            } finally {
                if (truststorePassword != null) {
                    Arrays.fill(truststorePassword, '\0');
                }
            }
        } else {
            // null keystore == the JVM's default trust anchors (cacerts).
            tmf.init((KeyStore) null);

            if (clientAuth != ClientAuth.NONE) {
                LOGGER.log(Level.WARNING,
                        "Client auth is {0} but no server.tls.truststore is "
                        + "set; client certificates will be validated against "
                        + "the JVM default trust anchors.", clientAuth);
            }
        }

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        LOGGER.log(Level.INFO,
                "TLS enabled (keystore: {0}, client auth: {1}{2}).",
                new Object[] {
                        keystorePath,
                        clientAuth,
                        protocols.isEmpty() ? "" : ", protocols: " + protocols });

        return new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters ssl = getSSLContext().getDefaultSSLParameters();

                if (!protocols.isEmpty()) {
                    ssl.setProtocols(protocols.toArray(new String[0]));
                }

                switch (clientAuth) {
                    case NEED:
                        ssl.setNeedClientAuth(true);
                        break;
                    case WANT:
                        ssl.setWantClientAuth(true);
                        break;
                    case NONE:
                    default:
                        break;
                }

                params.setSSLParameters(ssl);
            }
        };
    }

    /**
     * Loads a keystore file with the given type and password.
     */
    private static KeyStore loadStore(Path path, String type, char[] password)
            throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(type);

        try (InputStream is = Files.newInputStream(path)) {
            store.load(is, password);
        }

        return store;
    }

    /**
     * Resolves a password from a literal property first, then from the
     * environment variable named by the companion {@code *_env} property.
     *
     * @return The password, or {@code null} when neither source yields one.
     */
    private static char[] resolvePassword(
            Properties props, String literalKey, String envKey) {
        String literal = Checks.trimToNull(props.getProperty(literalKey));
        if (literal != null) {
            return literal.toCharArray();
        }

        String envName = Checks.trimToNull(props.getProperty(envKey));
        if (envName != null) {
            String value = Checks.trimToNull(System.getenv(envName));
            if (value != null) {
                return value.toCharArray();
            }

            LOGGER.log(Level.WARNING,
                    "{0} points at ''{1}'' but that variable is unset or blank.",
                    new Object[] { envKey, envName });
        }

        return null;
    }
}

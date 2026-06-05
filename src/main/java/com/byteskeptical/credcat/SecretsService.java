package com.byteskeptical.credcat;

import com.byteskeptical.credcat.config.KeeperConfig;
import com.byteskeptical.credcat.file.FileHandler;
import com.byteskeptical.credcat.file.FileTransport;
import com.byteskeptical.credcat.model.KeeperRequest;
import com.byteskeptical.credcat.model.SecretResponse;
import com.byteskeptical.credcat.util.Checks;
import com.byteskeptical.credcat.util.JsonHandler;
import com.keepersecurity.secretsManager.core.AccountNumber;
import com.keepersecurity.secretsManager.core.AddressRef;
import com.keepersecurity.secretsManager.core.BankAccounts;
import com.keepersecurity.secretsManager.core.BirthDate;
import com.keepersecurity.secretsManager.core.CardRef;
import com.keepersecurity.secretsManager.core.Date;
import com.keepersecurity.secretsManager.core.Email;
import com.keepersecurity.secretsManager.core.ExpirationDate;
import com.keepersecurity.secretsManager.core.FileRef;
import com.keepersecurity.secretsManager.core.HiddenField;
import com.keepersecurity.secretsManager.core.Hosts;
import com.keepersecurity.secretsManager.core.InMemoryStorage;
import com.keepersecurity.secretsManager.core.KeeperFile;
import com.keepersecurity.secretsManager.core.KeeperRecord;
import com.keepersecurity.secretsManager.core.KeeperRecordData;
import com.keepersecurity.secretsManager.core.KeeperRecordField;
import com.keepersecurity.secretsManager.core.KeeperSecrets;
import com.keepersecurity.secretsManager.core.KeyPairs;
import com.keepersecurity.secretsManager.core.KeyValueStorage;
import com.keepersecurity.secretsManager.core.LicenseNumber;
import com.keepersecurity.secretsManager.core.LocalConfigStorage;
import com.keepersecurity.secretsManager.core.Login;
import com.keepersecurity.secretsManager.core.Multiline;
import com.keepersecurity.secretsManager.core.Names;
import com.keepersecurity.secretsManager.core.OneTimeCode;
import com.keepersecurity.secretsManager.core.OneTimePassword;
import com.keepersecurity.secretsManager.core.Passkeys;
import com.keepersecurity.secretsManager.core.Password;
import com.keepersecurity.secretsManager.core.PaymentCards;
import com.keepersecurity.secretsManager.core.Phones;
import com.keepersecurity.secretsManager.core.PinCode;
import com.keepersecurity.secretsManager.core.SecretsManager;
import com.keepersecurity.secretsManager.core.SecretsManagerOptions;
import com.keepersecurity.secretsManager.core.SecureNote;
import com.keepersecurity.secretsManager.core.SecurityQuestions;
import com.keepersecurity.secretsManager.core.Text;
import com.keepersecurity.secretsManager.core.TotpCode;
import com.keepersecurity.secretsManager.core.Url;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.Security;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Your confidant in the cloud.
 */
public class SecretsService {

    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int THREADS =
            Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
    private static final Logger LOGGER = Logger.getLogger(
            SecretsService.class.getName()
    );
    private static final String CONFIG = "credcat.properties";
    private static final String DOMAIN = "keepersecurity.com";
    private static final String LOGGING = "logging.properties";
    private static final String VERSION = "1.1.0";

    static {
        initLogging();
        bouncyCastle();
    }

    private final AppConfig appConfig;

    /**
     * Initialize configuration.
     *
     * @param appConfig Tell me all the things you want me to do.
     */
    public SecretsService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * If you don't know, now you know.
     *
     * @return A string representing the service version.
     */
    public String getVersion() {
        Package pkg = SecretsService.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;

        return version != null ? version : VERSION;
    }

    /**
     * Load properties, provide sane defaults.
     */
    static class AppConfig {
        final boolean autoClean;
        final boolean persistentStorage;
        final FileTransport fileTransport;
        final int threads;
        final int maxRequestBytes;
        final int port;
        final KeeperConfig keeperConfig;
        final Path filesDir;
        final String clientKey;
        final String host;

        /**
         * Properties from classpath or filesystem, prioritizing filesystem.
         *
         * @throws IOException if reading properties fails.
         */
        AppConfig() throws IOException {
            Properties props = new Properties();

            // Load from Classpath
            try (InputStream is = SecretsService.class.getClassLoader()
                    .getResourceAsStream(CONFIG)) {
                if (is != null) {
                    props.load(is);
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Classpath config " + CONFIG + " could not be read.", e
                );
            }

            // Load from Filesystem
            File fsConfig = new File(CONFIG);
            if (fsConfig.exists() && fsConfig.isFile()) {
                try (FileInputStream fis = new FileInputStream(fsConfig)) {
                    props.load(fis);
                    LOGGER.log(Level.INFO,
                            "Loaded configuration from {0}",
                            fsConfig.getAbsolutePath()
                    );
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE,
                            "Found the config " + CONFIG
                            + " but failed to read it. Check the permissions.", e
                    );
                    throw e;
                }
            }

            this.autoClean = Boolean.parseBoolean(
                    props.getProperty("file.clean", "true"));
            this.clientKey = Checks.trimToNull(props.getProperty("keeper.client_key"));
            this.fileTransport = FileTransport.parse(
                    props.getProperty("file.transport"), FileTransport.INLINE);
            this.host = props.getProperty("server.host", "127.0.0.1");
            this.maxRequestBytes = parseInt(
                    props.getProperty("server.max_request_bytes"), MAX_REQUEST_BYTES);
            this.port = parseInt(props.getProperty("server.port"), 8888);
            this.threads = parseInt(
                    props.getProperty("server.threads"), THREADS);

            String filesProp = Checks.trimToNull(props.getProperty("keeper.files"));
            if (filesProp != null) {
                this.filesDir = Path.of(filesProp);
            } else {
                String osTemp = System.getProperty("java.io.tmpdir");
                this.filesDir = Path.of(osTemp, "credcat_" + UUID.randomUUID());
            }

            // Storage mode default is in-memory. LocalConfigStorage when
            // token refresh persistence is desired on a writable filesystem.
            this.persistentStorage = Boolean.parseBoolean(
                    props.getProperty("keeper.storage.persistent", "false"));

            // Default keeper.config, can be a file path or literal content.
            String configProp = Checks.trimToNull(props.getProperty("keeper.config"));
            String keeperConfig = null;

            if (configProp != null) {
                KeeperConfig bootstrap = new KeeperConfig(null, null, null);
                KeeperConfig.KSM seed = bootstrap.interpret(configProp);
                if (seed != null) {
                    keeperConfig = seed.getContent();
                }
            }

            // Named-config sources. Filesystem dir, env vars.
            String dirProp = Checks.trimToNull(props.getProperty("keeper.config.dir"));
            Path dirPath = dirProp != null ? Path.of(dirProp) : null;

            if (dirPath != null && !Files.isDirectory(dirPath)) {
                LOGGER.log(Level.WARNING,
                        "keeper.config.dir is not a directory; ignoring: {0}", dirPath);
                dirPath = null;
            }

            String envPrefix = Checks.trimToNull(props.getProperty("keeper.config.env"));
            this.keeperConfig = new KeeperConfig(keeperConfig, dirPath, envPrefix);
        }

        private static int parseInt(String s, int fallback) {
            if (Checks.isNullOrBlank(s)) {
                return fallback;
            }

            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING,
                        "Invalid numeric property ''{0}''; using default {1}.",
                        new Object[] { s, fallback });
                return fallback;
            }
        }
    }

    /**
     * Find KeeperRecords by UIDs and/or titles.
     * Uses the SDK's UID filter when only UIDs are supplied; otherwise fetches
     * once and filters in-process. Records are deduplicated by UID to handle
     * overlap between the two lookups.
     *
     * @param options A instance of SecretsManagerOptions to use in the lookup.
     * @param titles A list of strings representing KeeperRecord title names.
     * @param uids A list of strings representing KeeperRecord unique identifiers.
     * @return A list of KeeperRecords found or an empty list.
     */
    List<KeeperRecord> findRecords(
            SecretsManagerOptions options, List<String> titles, List<String> uids
    ) {
        boolean hasUids = !Checks.isNullOrEmpty(uids);
        boolean hasTitles = !Checks.isNullOrEmpty(titles);

        if (!hasUids && !hasTitles) {
            return Collections.emptyList();
        }

        Map<String, KeeperRecord> byUid = new LinkedHashMap<>();

        try {
            if (hasTitles) {
                // Title resolution requires a full fetch; piggy-back UID matching on it.
                KeeperSecrets all = SecretsManager.getSecrets(options);

                if (hasUids) {
                    Set<String> uidSet = new HashSet<>(uids);
                    for (KeeperRecord record : all.getRecords()) {
                        if (uidSet.contains(record.getRecordUid())) {
                            byUid.put(record.getRecordUid(), record);
                        }
                    }
                    LOGGER.log(Level.INFO,
                            "Matched {0} record(s) by UID from full fetch.", byUid.size());
                }

                for (String title : titles) {
                    KeeperRecord record = all.getSecretByTitle(title);
                    if (record != null) {
                        byUid.putIfAbsent(record.getRecordUid(), record);
                        LOGGER.log(Level.INFO,
                                "Found record by title: ''{0}''", title);
                    } else {
                        LOGGER.log(Level.WARNING,
                                "Record with title ''{0}'' not found.", title);
                    }
                }
            } else {
                // UID-only path: let the SDK do the server-side filter.
                KeeperSecrets byUidFetch = SecretsManager.getSecrets(options, uids);
                for (KeeperRecord record : byUidFetch.getRecords()) {
                    byUid.put(record.getRecordUid(), record);
                }
                LOGGER.log(Level.INFO, "Found {0} record(s) by UID.", byUid.size());
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Retrieving record(s) failed!", e);
            throw new RuntimeException(
                    "Failed to retrieve records: " + e.getMessage(), e);
        }

        return new ArrayList<>(byUid.values());
    }

    /**
     * Initialize secure storage then retrieve secrets.
     *
     * @param jsonRequest A JSON string representing the KeeperRequest.
     * @return A JSON string of the found secrets.
     * @throws Exception if the request fails to process.
     */
    public String getSecrets(String jsonRequest) throws Exception {
        LOGGER.log(Level.FINE, "Received request payload of {0} bytes",
                jsonRequest != null ? jsonRequest.length() : 0);

        KeeperRequest request = JsonHandler.fromJson(jsonRequest, KeeperRequest.class);
        if (request == null) {
            throw new IllegalArgumentException("Request body is empty.");
        }

        KeeperConfig.KSM ksm = appConfig.keeperConfig.resolve(
                request.getConfig(), request.getConfigName());
        if (ksm == null) {
            throw new IllegalArgumentException(
                    "No Keeper config provided in request or properties."
            );
        }

        String requestKey = request.getClientKey();
        String clientKey = !Checks.isNullOrBlank(requestKey)
                ? requestKey
                : appConfig.clientKey;

        FileTransport transport = FileTransport.parse(
                request.getFileTransport(), appConfig.fileTransport);
        String requestLocation = request.getSaveLocation();
        String saveLocation = !Checks.isNullOrBlank(requestLocation)
                ? requestLocation
                : appConfig.filesDir.toString();

        KeyValueStorage storage;
        try {
            storage = buildStorage(ksm);
        } catch (Exception e) {
            String errorMessage = "Loading of KSM vault config failed. "
                    + "Ensure the config is valid base64-encoded or JSON.";
            LOGGER.log(Level.SEVERE, errorMessage, e);
            throw new RuntimeException(errorMessage, e);
        }

        if (clientKey != null) {
            SecretsManager.initializeStorage(storage, clientKey, DOMAIN);
        }

        SecretsManagerOptions options = new SecretsManagerOptions(
                storage, SecretsManager::cachingPostFunction
        );

        FileHandler fileHandler = FileHandler.forTransport(
                transport, transport == FileTransport.DISK ? saveLocation : null);

        List<KeeperRecord> foundRecords = findRecords(
                options, request.getTitles(), request.getUids()
        );
        Map<String, Map<String, Object>> records = processRecords(
                foundRecords, fileHandler
        );

        return JsonHandler.toJson(records);
    }

    /**
     * Picks the Keeper storage impl. In-memory by default; file-backed when
     * persistent storage is enabled and the config came from a writable file.
     */
    KeyValueStorage buildStorage(KeeperConfig.KSM ksm) {
        if (appConfig.persistentStorage && !Checks.isNullOrBlank(ksm.getOrigin())) {
            Path path;
            try {
                path = Path.of(ksm.getOrigin());
            } catch (InvalidPathException e) {
                path = null;
            }

            if (path != null && Files.isRegularFile(path) && Files.isWritable(path)) {
                LOGGER.log(Level.FINE, "Using LocalConfigStorage at {0}", path);
                return new LocalConfigStorage(path.toString());
            }
        }

        LOGGER.log(Level.FINE, "Using InMemoryStorage for Keeper config.");
        return new InMemoryStorage(ksm.getContent());
    }

    /**
     * Downloads files attached to a KeeperRecord, provides its metadata.
     *
     * @param files A list of KeeperFile entries usually from a KeeperRecord.
     * @param handler The strategy to use for materializing each file.
     * @return A list of name, path file object details for downloaded files.
     */
    List<SecretResponse.FileInfo> processFiles(
            List<KeeperFile> files, FileHandler handler
    ) {
        if (Checks.isNullOrEmpty(files)) {
            return Collections.emptyList();
        }

        List<SecretResponse.FileInfo> fileInfos = new ArrayList<>(files.size());
        for (KeeperFile file : files) {
            if (file == null) {
                LOGGER.log(Level.WARNING,
                        "Encountered null KeeperFile entry; skipping.");
                continue;
            }
            try {
                fileInfos.add(handler.handle(file));
            } catch (Exception e) {
                String name = file.getData() != null
                        ? file.getData().getName()
                        : file.getFileUid();
                LOGGER.log(Level.SEVERE,
                        "Processing file [" + name + "] failed!", e);
            }
        }

        return fileInfos;
    }

    /**
     * Process record(s) field(s) values, organize in a structured format.
     *
     * @param records A list of KeeperRecord entries.
     * @param fileHandler The file handler chosen for this request.
     * @return A hashmap of credential fields and their values.
     */
    Map<String, Map<String, Object>> processRecords(
            List<KeeperRecord> records, FileHandler fileHandler
    ) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();

        for (KeeperRecord record : records) {
            if (record == null) {
                continue;
            }

            KeeperRecordData recordData = record.getData();
            final String recordUid = record.getRecordUid();
            Map<String, Object> recordDetails = new LinkedHashMap<>();
            Map<String, List<String>> fieldsMap = new LinkedHashMap<>();

            List<KeeperRecordField> fields = recordData.getFields();
            List<KeeperRecordField> customFields = recordData.getCustom();
            Stream<KeeperRecordField> allFields = Stream.concat(
                    fields != null ? fields.stream() : Stream.empty(),
                    customFields != null ? customFields.stream() : Stream.empty()
            );

            allFields.forEach(field -> {
                String label = field.getLabel();
                if (label == null) {
                    label = field.getClass().getSimpleName().toLowerCase(Locale.ROOT);
                }

                List<String> values = xtraxField(field);

                if (hasValue(values)) {
                    fieldsMap.put(label, values);
                } else {
                    LOGGER.log(Level.FINE,
                            "Skipped empty field value for field: {0}", label
                    );
                }
            });

            List<KeeperFile> files = record.getFiles();
            List<SecretResponse.FileInfo> filesList = processFiles(files, fileHandler);

            recordDetails.put("fields", fieldsMap);
            recordDetails.put("files", filesList);
            recordDetails.put("notes", recordData.getNotes());
            recordDetails.put("title", record.getTitle());
            recordDetails.put("type", record.getType());
            result.put(recordUid, recordDetails);
        }

        return result;
    }

    /**
     * Returns true if at least one value in the list is non-empty.
     */
    private static boolean hasValue(List<String> values) {
        if (Checks.isNullOrEmpty(values)) {
            return false;
        }

        for (String v : values) {
            if (!Checks.isNullOrBlank(v)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Maps a list of structured values to their string form, tolerating a null
     * input list and skipping null entries.
     *
     * @param values The source list (may be {@code null}).
     * @param fn     Converter from a non-null value to its string form.
     * @return A list of string forms, possibly empty, never {@code null}.
     */
    private static <T> List<String> mapValues(List<T> values, Function<T, String> fn) {
        if (Checks.isNullOrEmpty(values)) {
            return Collections.emptyList();
        }

        return values.stream()
                .filter(Objects::nonNull)
                .map(fn)
                .collect(Collectors.toList());
    }

    /**
     * Formats epoch-millis timestamps as ISO-8601 UTC. Used identically by
     * {@code BirthDate}, {@code Date}, and {@code ExpirationDate}
     *
     * @param timestamps A list of epoch-millisecond values; may be {@code null}.
     * @return ISO-8601 string forms.
     */
    private static List<String> formatTimestamps(List<Long> timestamps) {
        return mapValues(timestamps, ts -> Instant.ofEpochMilli(ts).toString());
    }

    /**
     * Extracts the value(s) from a KeeperRecordField to a data type. My shame :(
     *
     * @param field A KeeperRecordField to be extracted.
     * @return A List of string(s) or an empty list for unsupported field types.
     */
    List<String> xtraxField(KeeperRecordField field) {
        if (field instanceof Login) {
            return ((Login) field).getValue();
        } else if (field instanceof AccountNumber) {
            return ((AccountNumber) field).getValue();
        } else if (field instanceof AddressRef) {
            return ((AddressRef) field).getValue();
        } else if (field instanceof BankAccounts) {
            return mapValues(((BankAccounts) field).getValue(),
                ba -> String.format("Type: %s, Routing: %s, Account: %s, Other: %s",
                    ba.getAccountType(), ba.getRoutingNumber(),
                    ba.getAccountNumber(), ba.getOtherType()));
        } else if (field instanceof BirthDate) {
            return formatTimestamps(((BirthDate) field).getValue());
        } else if (field instanceof CardRef) {
            return ((CardRef) field).getValue();
        } else if (field instanceof Date) {
            return formatTimestamps(((Date) field).getValue());
        } else if (field instanceof Email) {
            return ((Email) field).getValue();
        } else if (field instanceof ExpirationDate) {
            return formatTimestamps(((ExpirationDate) field).getValue());
        } else if (field instanceof FileRef) {
            return ((FileRef) field).getValue();
        } else if (field instanceof HiddenField) {
            return ((HiddenField) field).getValue();
        } else if (field instanceof Hosts) {
            return mapValues(((Hosts) field).getValue(),
                h -> String.format("%s:%s", h.getHostName(), h.getPort()));
        } else if (field instanceof KeyPairs) {
            return mapValues(((KeyPairs) field).getValue(),
                kp -> String.format("Public Key: %s, Private Key: %s",
                    kp.getPublicKey(), kp.getPrivateKey()));
        } else if (field instanceof LicenseNumber) {
            return ((LicenseNumber) field).getValue();
        } else if (field instanceof Multiline) {
            return ((Multiline) field).getValue();
        } else if (field instanceof Names) {
            return mapValues(((Names) field).getValue(),
                n -> String.format("%s %s %s",
                    n.getFirst() != null ? n.getFirst() : "",
                    n.getMiddle() != null ? n.getMiddle() : "",
                    n.getLast() != null ? n.getLast() : "")
                    .trim().replaceAll("\\s+", " "));
        } else if (field instanceof OneTimeCode) {
            // OneTimeCode emits two values per source URL (code + timeLeft),
            // so it's the one case mapValues can't fully cover
            List<String> urls = ((OneTimeCode) field).getValue();
            if (Checks.isNullOrEmpty(urls)) {
                return Collections.emptyList();
            }

            List<String> result = new ArrayList<>(urls.size() * 2);
            for (String url : urls) {
                if (url == null) {
                    continue;
                }

                TotpCode totp = TotpCode.uriToTotpCode(url);
                result.add(totp.getCode());
                result.add(String.valueOf(totp.getTimeLeft()));
            }

            return result;
        } else if (field instanceof OneTimePassword) {
            return ((OneTimePassword) field).getValue();
        } else if (field instanceof Passkeys) {
            return mapValues(((Passkeys) field).getValue(),
                pk -> String.format("%s, %s, %s, %s, %s, %s, %s",
                    pk.getCredentialId(), pk.getSignCount(),
                    pk.getUserId(), pk.getRelyingParty(),
                    pk.getUsername(), pk.getCreatedDate(),
                    pk.getPrivateKey()));
        } else if (field instanceof Password) {
            return ((Password) field).getValue();
        } else if (field instanceof PaymentCards) {
            return mapValues(((PaymentCards) field).getValue(),
                pc -> String.format("%s, %s, %s",
                    pc.getCardNumber(),
                    pc.getCardExpirationDate(),
                    pc.getCardSecurityCode()));
        } else if (field instanceof Phones) {
            return mapValues(((Phones) field).getValue(),
                p -> String.format("%s, %s", p.getType(), p.getNumber()));
        } else if (field instanceof PinCode) {
            return ((PinCode) field).getValue();
        } else if (field instanceof SecureNote) {
            return ((SecureNote) field).getValue();
        } else if (field instanceof SecurityQuestions) {
            return mapValues(((SecurityQuestions) field).getValue(),
                sq -> String.format("%s, %s", sq.getQuestion(), sq.getAnswer()));
        } else if (field instanceof Text) {
            return ((Text) field).getValue();
        } else if (field instanceof Url) {
            return ((Url) field).getValue();
        } else {
            LOGGER.log(Level.WARNING,
                    "Skipped strange & unexpected field type: {0}",
                    field.getClass().getName()
            );

            return Collections.emptyList();
        }
    }

    /**
     * Don't shoot the messenger.
     *
     * @param exchange Encapsulation of methods for request received and response.
     * @param statusCode HTTP status of choice sent as response.
     * @param response What say you back?
     */
    private static void sendResponse(
            HttpExchange exchange, int statusCode, String response
    ) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Reads a bounded amount of bytes from the request body. Returns
     * {@code null} when the body exceeds the configured maximum, in which
     * case the handler should reply 413.
     */
    private static byte[] readRequest(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(
                Math.min(maxBytes, 8192));
        byte[] chunk = new byte[8192];
        int read;
        int total = 0;

        while ((read = in.read(chunk)) != -1) {
            total += read;

            if (total > maxBytes) {
                return null;
            }

            buf.write(chunk, 0, read);
        }

        return buf.toByteArray();
    }

    /**
     * Handles POST requests for secrets.
     */
    static class SecretsHandler implements HttpHandler {
        private final SecretsService service;
        private final int maxRequestBytes;

        SecretsHandler(SecretsService service) {
            this.service = service;
            this.maxRequestBytes = service.appConfig.maxRequestBytes;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405,
                        JsonHandler.envelope("error", "Method Not Allowed"));
                return;
            }

            try (InputStream is = exchange.getRequestBody()) {
                byte[] body = readRequest(is, maxRequestBytes);

                if (body == null) {
                    sendResponse(exchange, 413, JsonHandler.envelope("error",
                            "Request body exceeds " + maxRequestBytes + " bytes"));
                    return;
                }

                String request = new String(body, StandardCharsets.UTF_8);
                String response = service.getSecrets(request);

                sendResponse(exchange, 200, response);
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Bad Request: " + e.getMessage());
                sendResponse(exchange, 400,
                        JsonHandler.envelope("error", e.getMessage()));
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Internal Error", e);
                String detail = e.getMessage() != null
                        ? e.getMessage()
                        : e.getClass().getSimpleName();
                sendResponse(exchange, 500, JsonHandler.envelope("error",
                        "Internal Server Error: " + detail));
            }
        }
    }

    /**
     * Handles GET requests for version.
     */
    static class VersionHandler implements HttpHandler {
        private final SecretsService service;

        VersionHandler(SecretsService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405,
                        JsonHandler.envelope("error", "Method Not Allowed"));
                return;
            }

            sendResponse(exchange, 200,
                    JsonHandler.envelope("version", service.getVersion()));
        }
    }

    /**
     * Recursively wipes the files directory at shutdown so we don't litter
     * the host with credcat_* dirs. Gated solely by {@code file.clean}.
     */
    private static void cleanFilesDir(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            LOGGER.log(Level.FINE,
                                    "Failed to delete temp entry " + p, e);
                        }
                    });
        } catch (IOException e) {
            LOGGER.log(Level.FINE,
                    "Failed to walk temp directory " + dir, e);
        }
    }

    /**
     * Logging bootstrap. JUL only auto-loads from a system property;
     * we can't always set one, so we look in the classpath and then
     * alongside the JAR. Honors the user's
     * {@code -Djava.util.logging.config.file} when set.
     */
    private static void initLogging() {
        if (System.getProperty("java.util.logging.config.file") != null) {
            return;
        }

        // Filesystem first.
        File fs = new File(LOGGING);
        if (fs.isFile()) {
            try (FileInputStream fis = new FileInputStream(fs)) {
                LogManager.getLogManager().readConfiguration(fis);
                return;
            } catch (IOException e) {
                // fall through to classpath
            }
        }

        // Classpath fallback.
        try (InputStream is = SecretsService.class.getClassLoader()
                .getResourceAsStream(LOGGING)) {
            if (is != null) {
                LogManager.getLogManager().readConfiguration(is);
            }
        } catch (IOException ignored) {
            // Stick with JVM defaults.
        }
    }

    /**
     * Adds the BouncyCastle FIPS provider if it's on the classpath. Some
     * platforms ship without it; in that case we fall back to the platform
     * defaults and log a warning instead of dying.
     */
    private static void bouncyCastle() {
        try {
            Class<?> providerClass = Class.forName(
                    "org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider");
            java.security.Provider provider =
                    (java.security.Provider) providerClass
                            .getDeclaredConstructor().newInstance();
            Security.addProvider(provider);
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.WARNING,
                    "BouncyCastle FIPS provider not on classpath; "
                    + "using platform defaults.");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "Failed to install BouncyCastle FIPS provider; "
                    + "using platform defaults.", e);
        }
    }

    /**
     * Main method. Mode based on arguments.
     *
     * @param args payload based on KeeperRequests or "-server".
     */
    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();

        if (args.length == 0) {
            System.out.println(
                    "Usage: java -jar credcat.jar "
                    + "[-server | '<json_request>']\n"
            );
            System.out.println(
                    "Example: java -jar credcat.jar '{"
                    + "\"configName\":\"dev\", "
                    + "\"titles\":[\"RECORD_TITLES\"], \"uids\":[\"RECORD_UID\"], "
                    + "\"fileTransport\":\"INLINE\"}'"
            );

            return;
        }

        ExecutorService executor = null;

        try {
            final AppConfig config = new AppConfig();
            SecretsService service = new SecretsService(config);

            if (args[0].equals("-server")) {
                HttpServer server = HttpServer.create(
                        new InetSocketAddress(config.host, config.port), 0
                );

                server.createContext(
                        "/api/getSecrets",
                        new SecretsHandler(service)
                );
                server.createContext(
                        "/api/version",
                        new VersionHandler(service)
                );

                executor = new ThreadPoolExecutor(
                        config.threads, config.threads,
                        60L, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(config.threads * 4),
                        new ThreadPoolExecutor.CallerRunsPolicy()
                );
                final ExecutorService finalExecutor = executor;

                server.setExecutor(executor);
                server.start();

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    LOGGER.info("Herding credcat for you...");
                    server.stop(2);
                    finalExecutor.shutdown();

                    try {
                        if (!finalExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                            finalExecutor.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        finalExecutor.shutdownNow();
                        Thread.currentThread().interrupt();
                    }

                    if (config.autoClean) {
                        cleanFilesDir(config.filesDir);
                    }
                }));

                long elapsed = System.currentTimeMillis() - startTime;
                LOGGER.log(Level.INFO,
                        "Credcat started meowing in {0}ms on {1}:{2,number,#}",
                        new Object[] { elapsed, config.host, config.port });
            } else {
                try {
                    String response = service.getSecrets(args[0]);
                    LOGGER.log(Level.INFO, "--- Secrets Found ---");
                    System.out.println(response);
                } finally {
                    if (config.autoClean) {
                        cleanFilesDir(config.filesDir);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Execution failed in main method.", e);
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }
}

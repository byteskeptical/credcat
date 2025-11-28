package com.byteskeptical.credcat;

import com.byteskeptical.credcat.model.KeeperRequest;
import com.byteskeptical.credcat.model.SecretResponse;
import com.byteskeptical.credcat.util.JsonHandler;
import com.keepersecurity.secretsManager.core.AccountNumber;
import com.keepersecurity.secretsManager.core.AddressRef;
import com.keepersecurity.secretsManager.core.Addresses;
import com.keepersecurity.secretsManager.core.BankAccounts;
import com.keepersecurity.secretsManager.core.BirthDate;
import com.keepersecurity.secretsManager.core.CardRef;
import com.keepersecurity.secretsManager.core.Date;
import com.keepersecurity.secretsManager.core.Email;
import com.keepersecurity.secretsManager.core.ExpirationDate;
import com.keepersecurity.secretsManager.core.FileRef;
import com.keepersecurity.secretsManager.core.HiddenField;
import com.keepersecurity.secretsManager.core.Hosts;
import com.keepersecurity.secretsManager.core.KeeperFile;
import com.keepersecurity.secretsManager.core.KeeperRecord;
import com.keepersecurity.secretsManager.core.KeeperRecordData;
import com.keepersecurity.secretsManager.core.KeeperRecordField;
import com.keepersecurity.secretsManager.core.KeeperSecrets;
import com.keepersecurity.secretsManager.core.KeyPairs;
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
import com.keepersecurity.secretsManager.core.Url;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;

/**
 * Your confidant in the cloud.
 */
public class SecretsService {

    private static final String CONFIG = "credcat.properties";
    private static final String DOMAIN = "keepersecurity.com";
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Logger LOGGER = Logger.getLogger(SecretsService.class.getName());

    static {
        Security.addProvider(new BouncyCastleFipsProvider());
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
        return "1.0.0";
    }

    /**
     * Checks if a string is null or empty.
     *
     * @param str A string to check.
     * @return {@code true} if the string is null or empty, {@code false} otherwise.
     */
    public static boolean isNullOrEmpty(String str) {
        return str == null || str.isEmpty();
    }

    /**
     * Load properties, provide sane defaults.
     */
    static class AppConfig {
        final int port;
        final String clientKey;
        final String filesLocation;
        final String host;
        final String keeperConfig;

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
                               "Found config " + CONFIG
                               + " but failed to read it. Check your permissions.", e
                    );
                    throw e;
                }
            }

            String osTemp = System.getProperty("java.io.tmpdir");
            String keeperDir = "credcat_" + UUID.randomUUID().toString();
            String keeperFiles = Path.of(osTemp, keeperDir).toString();

            this.clientKey = props.getProperty("keeper.client_key", null);
            this.filesLocation = props.getProperty("keeper.files", keeperFiles);
            this.host = props.getProperty("server.host", "0.0.0.0");
            this.port = Integer.parseInt(props.getProperty("server.port", "8080"));
            String keepConf = props.getProperty("keeper.config");
            if (keepConf != null && !keepConf.isBlank()) {
                Path configPath = Path.of(keepConf);
                if (Files.exists(configPath)) {
                    this.keeperConfig = Files.readString(configPath);
                } else {
                    this.keeperConfig = keepConf;
                }
            } else {
                this.keeperConfig = null;
            }
        }
    }

    /**
     * Find KeeperRecords by UIDs and/or titles.
     * Uses the optimized UID filter for UIDs and iterates for titles.
     *
     * @param options A instance of SecretsManagerOptions to use in the lookup.
     * @param titles A list of strings representing KeeperRecord title names.
     * @param uids A list of strings representing KeeperRecord unique identifiers.
     * @return A list of KeeperRecords found or an empty list.
     */
    List<KeeperRecord> findRecords(
            SecretsManagerOptions options, List<String> titles, List<String> uids
    ) {
        Set<KeeperRecord> records = new HashSet<>();

        try {
            if (uids != null && !uids.isEmpty()) {
                KeeperSecrets recordsByUid = SecretsManager.getSecrets(options, uids);
                records.addAll(recordsByUid.getRecords());
                LOGGER.log(Level.INFO, "Found {0} records by UID.", records.size());
            }

            if (titles != null && !titles.isEmpty()) {
                KeeperSecrets recordsByTitle = SecretsManager.getSecrets(options);
                for (String title : titles) {
                    KeeperRecord record = recordsByTitle.getSecretByTitle(title);
                    if (record != null) {
                        records.add(record);
                        LOGGER.log(Level.INFO,
                                   "Found record by title: ''{0}''", title);
                    } else {
                        LOGGER.log(Level.WARNING,
                                   "Record with title ''{0}'' not found.", title);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Retrieving record(s) failed!", e);
        }

        return new ArrayList<>(records);
    }

    /**
     * Initialize secure storage then retrieve secrets.
     *
     * @param jsonRequest A JSON string representing the KeeperRequest.
     * @return A JSON string of the found secrets.
     * @throws Exception if the request fails to process.
     */
    public String getSecrets(String jsonRequest) throws Exception {
        KeeperRequest request = JsonHandler.fromJson(jsonRequest, KeeperRequest.class);

        String keeperConfig = request.getConfig();
        if (isNullOrEmpty(keeperConfig)) {
            if (appConfig.keeperConfig == null) {
                throw new IllegalArgumentException(
                        "No Keeper config provided in request or properties."
                );
            }
            keeperConfig = appConfig.keeperConfig;
        }

        String clientKey = request.getClientKey();
        if (isNullOrEmpty(clientKey)) {
            if (appConfig.clientKey == null) {
                clientKey = null;
            } else {
                clientKey = appConfig.clientKey;
            }
        }

        String saveLocation = request.getSaveLocation();
        if (isNullOrEmpty(saveLocation)) {
            saveLocation = appConfig.filesLocation;
        }

        LocalConfigStorage storage = null;
        try {
            storage = new LocalConfigStorage(keeperConfig);
        } catch (Exception e) {
            String errorMessage = "Error loading KSM Config. Make sure "
                    + keeperConfig
                    + " contains a valid base64 encoded JSON config.";
            LOGGER.log(Level.SEVERE, errorMessage, e);
            throw new RuntimeException(errorMessage, e);
        }

        if (clientKey != null) {
            SecretsManager.initializeStorage(storage, clientKey, DOMAIN);
        }

        SecretsManagerOptions options = null;
        options = new SecretsManagerOptions(
                storage, SecretsManager::cachingPostFunction
        );

        List<KeeperRecord> foundRecords = findRecords(
                options, request.getTitles(), request.getUids()
        );
        Map<String, Map<String, Object>> records = processRecords(
                foundRecords, saveLocation
        );

        return JsonHandler.toJson(records);
    }

    /**
     * Downloads files attached to a KeeperRecord, provides its metadata.
     *
     * @param files A list of KeeperFile entries usually from a KeeperRecord.
     * @param saveLocation Local path to files save directory.
     * @return A list of name, path file object details for downloaded files.
     * @throws IOException if a file operation fails.
     */
    List<SecretResponse.FileInfo> processFiles(
            List<KeeperFile> files, String saveLocation
    ) throws IOException {
        Path downloadDir = Path.of(saveLocation);

        if (!Files.exists(downloadDir)) {
            Files.createDirectories(downloadDir);
        }

        List<SecretResponse.FileInfo> fileInfos = new ArrayList<>();
        for (KeeperFile file : files) {
            if (file == null) {
                System.out.println("Could not find file with UID: " + file + " in record");
                continue;
            }
            byte[] fileBytes = SecretsManager.downloadFile(file);

            String name = file.getData().getName();
            Path filePath = downloadDir.resolve(name);
            try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                fos.write(fileBytes);
                fileInfos.add(new SecretResponse.FileInfo(name, filePath.toString()));
                LOGGER.log(Level.INFO, "Successfully downloaded & saved file: {0}", name);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Saving file [" + name + "] failed!", e);
            }
        }

        return fileInfos;
    }

    /**
     * Process record(s) field(s) values, organize in a structured format.
     *
     * @param record A KeeperRecord entry.
     * @param saveLocation Local path to files save directory.
     * @return A hashmap of credential fields and their values.
     * @throws IOException if a file operation fails during processing.
     */
    Map<String, Map<String, Object>> processRecords(
            List<KeeperRecord> records, String saveLocation
    ) throws IOException {
        Map<String, Map<String, Object>> result = new HashMap<>();

        for (KeeperRecord record : records) {
            if (record == null) {
                continue;
            }

            KeeperRecordData recordData = record.getData();
            final String recordUid = record.getRecordUid();
            Map<String, Object> recordDetails = new HashMap<>();
            Map<String, List<String>> fieldsMap = new HashMap<>();
            List<SecretResponse.FileInfo> filesList = new ArrayList<>();

            List<KeeperRecordField> fields = recordData.getFields();
            List<KeeperRecordField> customFields = recordData.getCustom();
            Stream<KeeperRecordField> allFields = Stream.concat(fields.stream(),
                                                                customFields.stream());
            allFields.forEach(field -> {
                String label = field.getLabel();
                if (label == null) {
                    label = field.getClass().getSimpleName().toLowerCase();
                }

                List<String> values = xtraxField(field);

                if (values != null && !values.isEmpty() && !isNullOrEmpty(values.get(0))) {
                    fieldsMap.put(label, values);
                } else {
                    LOGGER.log(Level.FINE,
                               "Skipped empty field value for field: {0}", label
                    );
                }
            });

            List<KeeperFile> files = record.getFiles();
            if (files != null) {
                filesList.addAll(processFiles(files, saveLocation));
            }

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
            return ((BankAccounts) field).getValue().stream()
                .map(ba -> {
                    if (ba == null) {
                        return null;
                    }
                    return String.format("Type: %s, Routing: %s, Account: %s, Other: %s",
                        ba.getAccountType(), ba.getRoutingNumber(),
                        ba.getAccountNumber(), ba.getOtherType());
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } else if (field instanceof CardRef) {
            return ((CardRef) field).getValue();
        } else if (field instanceof Date) {
            return ((Date) field).getValue().stream()
                .map(Object::toString)
                .collect(Collectors.toList());
        } else if (field instanceof Email) {
            return ((Email) field).getValue();
        } else if (field instanceof ExpirationDate) {
            return ((ExpirationDate) field).getValue().stream()
                .map(Object::toString)
                .collect(Collectors.toList());
        } else if (field instanceof FileRef) {
            return ((FileRef) field).getValue();
        } else if (field instanceof HiddenField) {
            return ((HiddenField) field).getValue();
        } else if (field instanceof Hosts) {
            return ((Hosts) field).getValue().stream()
                .map(h -> {
                    if (h == null) {
                        return null;
                    }
                    return String.format("%s:%s", h.getHostName(), h.getPort());
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } else if (field instanceof KeyPairs) {
            return ((KeyPairs) field).getValue().stream()
                .map(kp -> {
                    if (kp == null) {
                        return null;
                    }
                    return String.format("Public Key: %s, Private Key: %s",
                        kp.getPublicKey(), kp.getPrivateKey());
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } else if (field instanceof LicenseNumber) {
            return ((LicenseNumber) field).getValue();
        } else if (field instanceof Multiline) {
            return ((Multiline) field).getValue();
        } else if (field instanceof Names) {
            return ((Names) field).getValue().stream()
                .map(n -> {
                    if (n == null) {
                        return null;
                    }
                    return String.format("%s %s %s",
                        n.getFirst() != null ? n.getFirst() : "",
                        n.getMiddle() != null ? n.getMiddle() : "",
                        n.getLast() != null ? n.getLast() : ""
                    ).trim();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } else if (field instanceof OneTimeCode) {
            return ((OneTimeCode) field).getValue();
        } else if (field instanceof OneTimePassword) {
            return ((OneTimePassword) field).getValue();
        } else if (field instanceof Passkeys) {
            return ((Passkeys) field).getValue().stream()
                .map(pk -> {
                    if (pk == null) {
                        return null;
                    }
                    return String.format("%s, %s, %s, %s, %s, %s, %s",
                        pk.getCredentialId(),
                        pk.getSignCount(),
                        pk.getUserId(),
                        pk.getRelyingParty(),
                        pk.getUsername(),
                        pk.getCreatedDate(),
                        pk.getPrivateKey()
                    );
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } else if (field instanceof Password) {
            return ((Password) field).getValue();
        } else if (field instanceof PaymentCards) {
            return ((PaymentCards) field).getValue().stream()
                .map(pc -> {
                    if (pc == null) {
                        return null;
                    }
                    return String.format("%s, %s, %s",
                        pc.getCardNumber(), pc.getCardExpirationDate(), pc.getCardSecurityCode());
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } else if (field instanceof Phones) {
            return ((Phones) field).getValue().stream()
                .map(p -> {
                    if (p == null) {
                        return null;
                    }
                    return String.format("%s, %s", p.getType(), p.getNumber());
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } else if (field instanceof PinCode) {
            return ((PinCode) field).getValue();
        } else if (field instanceof SecureNote) {
            return ((SecureNote) field).getValue();
        } else if (field instanceof SecurityQuestions) {
            return ((SecurityQuestions) field).getValue().stream()
                .map(sq -> {
                    if (sq == null) {
                        return null;
                    }
                    return String.format("%s, %s",
                        sq.getQuestion(), sq.getAnswer());
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
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
     * @param statusCode HTTP status of choice.
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
     * Handles POST requests for secrets.
     */
    static class SecretsHandler implements HttpHandler {
        private final SecretsService service;
        private final AppConfig appConfig;

        SecretsHandler(SecretsService service, AppConfig appConfig) {
            this.service = service;
            this.appConfig = appConfig;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\": \"Method Not Allowed\"}");
                return;
            }

            try (InputStream is = exchange.getRequestBody()) {
                String request = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                String response = service.getSecrets(request);

                sendResponse(exchange, 200, response);
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Bad Request: " + e.getMessage());
                sendResponse(exchange, 400, "{\"error\": \"" + e.getMessage() + "\"}");
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Internal Error", e);
                sendResponse(exchange, 500,
                             "{\"error\": \"Internal Server Error: " + e.getMessage() + "\"}"
                );
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
                             "{\"error\": \"Method Not Allowed\"}"
                );
                return;
            }

            sendResponse(exchange, 200,
                         "{\"version\": \"" + service.getVersion() + "\"}"
            );
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
            System.out.println("Usage: java -jar credcat.jar [-server | '<json_request>']");
            System.out.println(
                    "Example: java -jar credcat.jar '{"
                    + "\"config\":\"config.json\", "
                    + "\"titles\":[\"RECORD_TITLES\"], \"uids\":[\"RECORD_UID\"]}'"
            );

            return;
        }

        try {
            AppConfig config = new AppConfig();
            SecretsService service = new SecretsService(config);

            if (args[0].equals("-server")) {
                HttpServer server = HttpServer.create(
                    new InetSocketAddress(config.host, config.port), 0
                );

                server.createContext(
                        "/api/getSecrets",
                        new SecretsHandler(service, config)
                );
                server.createContext(
                        "/version",
                        new VersionHandler(service)
                );

                server.setExecutor(EXECUTOR);
                server.start();

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    LOGGER.info("Herding credcat for you...");
                    server.stop(2);
                    EXECUTOR.shutdown();

                    try {
                        if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                            EXECUTOR.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        EXECUTOR.shutdownNow();
                    }
                }));

                LOGGER.log(Level.INFO,
                        "Credcat started meowing in {0}ms on {1}:{2,number,#}",
                        new Object[] {
                          (System.currentTimeMillis() - startTime),
                          config.host,
                          config.port
                        }
                );
            } else {
                String response = service.getSecrets(args[0]);
                LOGGER.log(Level.INFO, "--- Secrets Found ---");
                System.out.println(response);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Execution failed in main method.", e);
        }
    }
}

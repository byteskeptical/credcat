package com.byteskeptical.credcat;

import com.byteskeptical.credcat.SecretsService.AppConfig;
import com.keepersecurity.secretsManager.core.BankAccount;
import com.keepersecurity.secretsManager.core.BankAccounts;
import com.keepersecurity.secretsManager.core.KeeperRecord;
import com.keepersecurity.secretsManager.core.KeeperRecordData;
import com.keepersecurity.secretsManager.core.KeeperRecordField;
import com.keepersecurity.secretsManager.core.Login;
import com.keepersecurity.secretsManager.core.Password;
import com.keepersecurity.secretsManager.core.Url;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class SecretsServiceTest {

    private static final String osTemp = System.getProperty("java.io.tmpdir");
    private static final String keeperDir = "credcat_" + UUID.randomUUID().toString();

    private static final String TEST_CLIENT_KEY = "7dae669a419ee250d0fd0e12d527f5f1";
    private static final String TEST_KEEPER_CONFIG = "base64-json-secret";
    private static final String TEST_SAVE_PATH = Path.of(osTemp, keeperDir).toString();
    private static final String TEST_UID = "7bN_ceW-p3_alVUNmI09Tw";
    private static final String TEST_TITLE = "Credcat Record";

    @Mock private AppConfig mockConfig;
    @Mock private KeeperRecord mockRecord;
    @Mock private KeeperRecordData mockRecordData;

    private SecretsService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new SecretsService(mockConfig);

        confInjection(mockConfig, "filesLocation", TEST_SAVE_PATH);
        confInjection(mockConfig, "clientKey", TEST_CLIENT_KEY);
        confInjection(mockConfig, "keeperConfig", TEST_KEEPER_CONFIG);
        
        lenient().when(mockRecord.getData()).thenReturn(mockRecordData);
        lenient().when(mockRecord.getRecordUid()).thenReturn(TEST_UID);
        lenient().when(mockRecordData.getCustom()).thenReturn(Collections.emptyList());
        lenient().when(mockRecordData.getFields()).thenReturn(Collections.emptyList());
    }

    @DisplayName("Version Check: Return the semantic version")
    @Test
    void getVersion_returnsCorrectVersion() {
        assertEquals("1.0.0", service.getVersion(), "Version should match the defined string.");
    }

    @DisplayName("Null or Empty Check: Input validation")
    @Test
    void isNullOrEmpty_returnsCorrectBoolean() {
        assertTrue(SecretsService.isNullOrEmpty(null),
                   "Should return true for null string."
        );
        assertTrue(SecretsService.isNullOrEmpty(""),
                   "Should return true for empty string."
        );
        assertFalse(SecretsService.isNullOrEmpty(" "),
                    "Should return false for a string with spaces."
        );
        assertFalse(SecretsService.isNullOrEmpty("text"),
                    "Should return false for a non-empty string."
        );
    }

    @DisplayName("Process Records: Empty list returns empty map")
    @Test
    void processRecords_withEmptyList_returnsEmptyMap() throws IOException {
        Map<String, Map<String, Object>> result = service.processRecords(
            Collections.emptyList(), TEST_SAVE_PATH
        );
        assertTrue(result.isEmpty(), "Empty input begets empty map.");
    }

    @DisplayName("Process Records: Standard fields mapping")
    @Test
    void processRecords_withFields_mapsCorrectly() throws IOException {
        when(mockRecord.getTitle()).thenReturn(TEST_TITLE);
        when(mockRecord.getType()).thenReturn("login");

        Login login = mock(Login.class);
        when(login.getLabel()).thenReturn("username");
        when(login.getValue()).thenReturn(List.of("admin"));

        Password password = mock(Password.class);
        when(password.getLabel()).thenReturn("password");
        when(password.getValue()).thenReturn(List.of("123456"));

        when(mockRecordData.getFields()).thenReturn(List.of(login, password));

        Map<String, Map<String, Object>> result = service.processRecords(
            List.of(mockRecord), TEST_SAVE_PATH
        );

        assertNotNull(result.get(TEST_UID));
        Map<String, Object> details = result.get(TEST_UID);
        
        assertEquals(TEST_TITLE, details.get("title"));
        assertEquals("login", details.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, List<String>> fields = (Map<String, List<String>>) details.get("fields");

        assertEquals("admin", fields.get("username").get(0));
        assertEquals("123456", fields.get("password").get(0));
    }

    @DisplayName("Process Records: Null label uses class name fallback")
    @Test
    void processRecords_nullLabel_usesClassName() throws IOException {
        Url urlField = mock(Url.class);
        when(urlField.getLabel()).thenReturn(null);
        when(urlField.getValue()).thenReturn(List.of("https://example.com"));

        when(mockRecordData.getFields()).thenReturn(List.of(urlField));

        Map<String, Map<String, Object>> result = service.processRecords(
            List.of(mockRecord), TEST_SAVE_PATH
        );

        @SuppressWarnings("unchecked")
        Map<String, List<String>> fields = (Map<String, List<String>>) result.get(TEST_UID).get("fields");
        
        assertTrue(fields.containsKey("url"),
                   "Fallback to simple class name on null label"
        );
        assertEquals("https://example.com", fields.get("url").get(0));
    }

    @ParameterizedTest(name = "{index} => Field: {0}, Expected: {1}")
    @MethodSource("fieldScenarios")
    @DisplayName("XtraxField: Extracts values from all SDK types")
    void xtraxField_mapsCorrectly(
            String testName,
            List<String> expectedValue,
            KeeperRecordField field
    ) {
        List<String> fieldValue = service.xtraxField(field);
        assertEquals(expectedValue, fieldValue,
                     "Failed to extract values for " + testName
        );
    }

    /**
     * Data provider for parameterized test.
     * (TestName, ExpectedOutput, MockField)
     */
    private static Stream<Arguments> fieldScenarios() {
        Login login = mock(Login.class);
        when(login.getValue()).thenReturn(List.of("user1"));

        Url url = mock(Url.class);
        when(url.getValue()).thenReturn(List.of("http://localhost"));

        BankAccounts bankField = mock(BankAccounts.class);
        BankAccount bankData = mock(BankAccount.class);
        when(bankData.getAccountType()).thenReturn("Checking");
        when(bankData.getRoutingNumber()).thenReturn("123");
        when(bankData.getAccountNumber()).thenReturn("456");
        when(bankData.getOtherType()).thenReturn("N/A");
        when(bankField.getValue()).thenReturn(List.of(bankData));

        String bankString = "Type: Checking, Routing: 123, Account: 456, Other: N/A";

        // Unknown Type
        KeeperRecordField unknown = mock(KeeperRecordField.class);

        return Stream.of(
            Arguments.of("BankAccounts", List.of(bankString), bankField),
            Arguments.of("Login", List.of("user1"), login),
            Arguments.of("Unknown Type", Collections.emptyList(), unknown),
            Arguments.of("Url", List.of("http://localhost"), url)
        );
    }

    /**
     * Inject dependencies into the AppConfig mock.
     * Avoids reading from the disk or creating constructor chains for testing.
     */
    private void confInjection(
            Object target, String fieldName, Object value
    ) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

}

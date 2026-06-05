package com.byteskeptical.credcat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.byteskeptical.credcat.SecretsService.AppConfig;
import com.byteskeptical.credcat.file.FileHandler;
import com.byteskeptical.credcat.file.FileTransport;
import com.keepersecurity.secretsManager.core.BankAccount;
import com.keepersecurity.secretsManager.core.BankAccounts;
import com.keepersecurity.secretsManager.core.KeeperRecord;
import com.keepersecurity.secretsManager.core.KeeperRecordData;
import com.keepersecurity.secretsManager.core.KeeperRecordField;
import com.keepersecurity.secretsManager.core.Login;
import com.keepersecurity.secretsManager.core.Password;
import com.keepersecurity.secretsManager.core.Url;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link SecretsService} record/field extraction and versioning.
 */
@ExtendWith(MockitoExtension.class)
class SecretsServiceTest {

    private static final String TEST_UID = "7bN_ceW-p3_alVUNmI09Tw";
    private static final String TEST_TITLE = "Credcat Record";

    @Mock private AppConfig mockConfig;
    @Mock private KeeperRecord mockRecord;
    @Mock private KeeperRecordData mockRecordData;

    private SecretsService service;
    private FileHandler fileHandler;

    @BeforeEach
    void setUp() throws IOException {
        service = new SecretsService(mockConfig);

        // NONE transport touches no disk -- ideal for record/field unit tests.
        fileHandler = FileHandler.forTransport(FileTransport.NONE, null);

        lenient().when(mockRecord.getData()).thenReturn(mockRecordData);
        lenient().when(mockRecord.getRecordUid()).thenReturn(TEST_UID);
        lenient().when(mockRecordData.getCustom()).thenReturn(Collections.emptyList());
        lenient().when(mockRecordData.getFields()).thenReturn(Collections.emptyList());
    }

    @DisplayName("Version Check: Reports a semantic version")
    @Test
    void getVersion_isSemantic() {
        String version = service.getVersion();

        assertNotNull(version, "Version should never be null.");
        assertTrue(
            version.matches("\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?"),
            "Version should be semantic (MAJOR.MINOR.PATCH), got: " + version
        );
    }

    @DisplayName("Process Records: Empty list returns empty map")
    @Test
    void processRecords_withEmptyList_returnsEmptyMap() {
        Map<String, Map<String, Object>> result = service.processRecords(
            Collections.emptyList(), fileHandler
        );

        assertTrue(result.isEmpty(), "Empty input begets empty map.");
    }

    @DisplayName("Process Records: Standard fields mapping")
    @Test
    void processRecords_withFields_mapsCorrectly() {
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
            List.of(mockRecord), fileHandler
        );

        assertNotNull(result.get(TEST_UID));
        Map<String, Object> details = result.get(TEST_UID);

        assertEquals(TEST_TITLE, details.get("title"));
        assertEquals("login", details.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, List<String>> fields =
                (Map<String, List<String>>) details.get("fields");

        assertEquals("admin", fields.get("username").get(0));
        assertEquals("123456", fields.get("password").get(0));
    }

    @DisplayName("Process Records: Null label uses class name fallback")
    @Test
    void processRecords_nullLabel_usesClassName() {
        Url urlField = mock(Url.class);
        when(urlField.getLabel()).thenReturn(null);
        when(urlField.getValue()).thenReturn(List.of("https://example.com"));

        when(mockRecordData.getFields()).thenReturn(List.of(urlField));

        Map<String, Map<String, Object>> result = service.processRecords(
            List.of(mockRecord), fileHandler
        );

        @SuppressWarnings("unchecked")
        Map<String, List<String>> fields =
                (Map<String, List<String>>) result.get(TEST_UID).get("fields");

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
     * Data provider for the parameterized test.
     * (TestName, ExpectedOutput, MockField)
     *
     * <p>Stubs are lenient: every mock is built up front but each test
     * invocation exercises only one, so strict stubbing would flag the rest.</p>
     */
    private static Stream<Arguments> fieldScenarios() {
        Login login = mock(Login.class);
        lenient().when(login.getValue()).thenReturn(List.of("user1"));

        Url url = mock(Url.class);
        lenient().when(url.getValue()).thenReturn(List.of("http://localhost"));

        BankAccounts bankField = mock(BankAccounts.class);
        BankAccount bankData = mock(BankAccount.class);
        lenient().when(bankData.getAccountType()).thenReturn("Checking");
        lenient().when(bankData.getRoutingNumber()).thenReturn("123");
        lenient().when(bankData.getAccountNumber()).thenReturn("456");
        lenient().when(bankData.getOtherType()).thenReturn("N/A");
        lenient().when(bankField.getValue()).thenReturn(List.of(bankData));

        String bankString = "Type: Checking, Routing: 123, Account: 456, Other: N/A";

        // Unknown type, no stubs needed, Falls through to the empty list.
        KeeperRecordField unknown = mock(KeeperRecordField.class);

        return Stream.of(
            Arguments.of("BankAccounts", List.of(bankString), bankField),
            Arguments.of("Login", List.of("user1"), login),
            Arguments.of("Unknown Type", Collections.emptyList(), unknown),
            Arguments.of("Url", List.of("http://localhost"), url)
        );
    }

}

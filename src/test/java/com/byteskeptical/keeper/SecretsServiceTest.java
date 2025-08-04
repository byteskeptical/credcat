package com.byteskeptical.keeper;

import com.keepersecurity.secretsManager.core.KeeperRecord;
import com.keepersecurity.secretsManager.core.KeeperRecordData;
import com.keepersecurity.secretsManager.core.KeeperRecordField;
import com.keepersecurity.secretsManager.core.Login;
import com.keepersecurity.secretsManager.core.Password;
import com.keepersecurity.secretsManager.core.Url;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class SecretsServiceTest {

    @Mock
    private KeeperRecord mockRecord;
    @Mock
    private KeeperRecordData mockRecordData;
    @Mock
    private Login mockLoginField;
    @Mock
    private Password mockPasswordField;
    @Mock
    private Url mockUrlField;

    private SecretsService service;

    @BeforeEach
    void setUp() {
        service = new SecretsService();
        lenient().when(mockRecord.getData()).thenReturn(mockRecordData);
        lenient().when(mockRecordData.getCustom()).thenReturn(Collections.emptyList());
    }

    @Test
    void getVersion_returnsCorrectVersion() {
        assertEquals("0.1337", service.getVersion(), "Version should match the defined string.");
    }

    @Test
    void isNullOrEmpty_returnsCorrectBoolean() {
        assertTrue(SecretsService.isNullOrEmpty(null), "Should return true for null string.");
        assertTrue(SecretsService.isNullOrEmpty(""), "Should return true for empty string.");
        assertFalse(SecretsService.isNullOrEmpty(" "), "Should return false for a string with spaces.");
        assertFalse(SecretsService.isNullOrEmpty("text"), "Should return false for a non-empty string.");
    }

    @Test
    void processRecords_withEmptyList_returnsEmptyMap() throws IOException {
        Map<String, Map<String, Object>> result = service.processRecords(Collections.emptyList());
        assertTrue(result.isEmpty(), "Processing an empty list should result in an empty map.");
    }

    @Test
    void processRecords_withVariousFieldTypes_mapsAllCorrectly() throws IOException {
        when(mockRecord.getRecordUid()).thenReturn("test-uid-123");
        when(mockRecord.getTitle()).thenReturn("My Test Record");
        when(mockRecord.getType()).thenReturn("login");

        when(mockLoginField.getValue()).thenReturn(List.of("my-username"));
        when(mockLoginField.getLabel()).thenReturn("username");
        when(mockPasswordField.getValue()).thenReturn(List.of("my-secret-password"));
        when(mockPasswordField.getLabel()).thenReturn("password");
        when(mockUrlField.getValue()).thenReturn(List.of("https://test.byteskeptical.com"));
        when(mockUrlField.getLabel()).thenReturn("url");

        List<KeeperRecordField> fields = new ArrayList<>();
        fields.add(mockLoginField);
        fields.add(mockPasswordField);
        fields.add(mockUrlField);
        when(mockRecordData.getFields()).thenReturn(fields);

        Map<String, Map<String, Object>> result = service.processRecords(List.of(mockRecord));

        assertNotNull(result, "Result map should not be null");
        assertTrue(result.containsKey("test-uid-123"), "Result should contain the record UID as a key");

        Map<String, Object> recordDetails = result.get("test-uid-123");
        assertEquals("My Test Record", recordDetails.get("title"), "Title should be correctly mapped");
        assertEquals("login", recordDetails.get("type"), "Type should be correctly mapped");

        @SuppressWarnings("unchecked")
        Map<String, List<String>> mappedFields = (Map<String, List<String>>) recordDetails.get("fields");
        assertNotNull(mappedFields, "Fields map should not be null");
        assertEquals("my-username", mappedFields.get("username").get(0), "Username should be correct");
        assertEquals("my-secret-password", mappedFields.get("password").get(0), "Password should be correct");
        assertEquals("https://test.byteskeptical.com", mappedFields.get("url").get(0), "URL should be correct");
    }

    @Test
    void xtraxField_withUnknownType_returnsEmptyList() {
        KeeperRecordField unknownField = mock(KeeperRecordField.class);

        List<String> result = service.xtraxField(unknownField);

        assertNotNull(result, "Result should not be null.");
        assertTrue(result.isEmpty(), "Result for an unknown field type should be an empty list.");
    }
}

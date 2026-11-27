package com.yadaventerprise.phoneserver.data.reliability;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class JsonRecordValidatorTest {

    @Test
    public void acceptsValidRecordWithRequiredFields() {
        JsonRecordValidator.Result result = JsonRecordValidator.validate(
                "{\"id\":\"one\",\"time\":123,\"nested\":{\"ok\":true}}", "id", "time");
        assertTrue(result.valid);
        assertTrue(result.errors.isEmpty());
    }

    @Test
    public void rejectsTruncatedJson() {
        JsonRecordValidator.Result result = JsonRecordValidator.validate(
                "{\"id\":\"one\",\"nested\":{\"ok\":true}", "id");
        assertFalse(result.valid);
        assertTrue(result.errors.toString().contains("unbalanced"));
    }

    @Test
    public void rejectsMissingRequiredFields() {
        JsonRecordValidator.Result result = JsonRecordValidator.validate("{\"id\":\"one\"}", "id", "checksum");
        assertFalse(result.valid);
        assertTrue(result.errors.toString().contains("checksum"));
    }

    @Test
    public void rejectsEmptyRecord() {
        assertFalse(JsonRecordValidator.validate("  ").valid);
    }
}

package com.yadaventerprise.phoneserver.data.reliability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class JsonRecordValidator {

    private JsonRecordValidator() {
    }

    public static Result validate(String content, String... requiredKeys) {
        Result result = new Result();
        if (content == null || content.trim().isEmpty()) {
            result.errors.add("record is empty");
            return result;
        }
        String text = content.trim();
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    result.errors.add("unbalanced closing bracket");
                    return result;
                }
            }
        }
        if (inString) result.errors.add("unterminated string");
        if (depth > 0) result.errors.add("truncated or unbalanced JSON");
        if (requiredKeys != null) {
            for (String key : requiredKeys) {
                if (key != null && !key.isEmpty() && !text.contains("\"" + key + "\"")) {
                    result.errors.add("missing field: " + key);
                }
            }
        }
        result.valid = result.errors.isEmpty();
        return result;
    }

    public static final class Result {
        public boolean valid;
        public final List<String> errors = new ArrayList<>();

        public List<String> getErrors() {
            return Collections.unmodifiableList(errors);
        }
    }
}

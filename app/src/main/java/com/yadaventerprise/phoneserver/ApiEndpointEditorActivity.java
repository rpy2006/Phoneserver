package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen add/edit form for one custom /api/* endpoint: path, method,
 * response headers, documented parameters, and the JSON response body.
 */
public class ApiEndpointEditorActivity extends AppCompatActivity {

    public static final String EXTRA_EDIT_PATH = "edit_path";
    public static final String EXTRA_EDIT_METHOD = "edit_method";

    private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE"};

    private EditText endpointInput, bodyInput;
    private Spinner methodSpinner;
    private TextView lineNumbers;
    private android.widget.LinearLayout headersContainer, paramsContainer;

    private String originalPath = null;
    private String originalMethod = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_api_endpoint_editor);

        endpointInput = findViewById(R.id.endpoint_input);
        bodyInput = findViewById(R.id.body_input);
        methodSpinner = findViewById(R.id.method_spinner);
        lineNumbers = findViewById(R.id.line_numbers);
        headersContainer = findViewById(R.id.headers_container);
        paramsContainer = findViewById(R.id.params_container);

        ArrayAdapter<String> methodAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, METHODS);
        methodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        methodSpinner.setAdapter(methodAdapter);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.save_button).setOnClickListener(v -> save());
        findViewById(R.id.add_header_button).setOnClickListener(v -> addRow(headersContainer, "", ""));
        findViewById(R.id.add_param_button).setOnClickListener(v -> addRow(paramsContainer, "", ""));

        ImageView deleteButton = findViewById(R.id.delete_button);
        deleteButton.setOnClickListener(v -> {
            if (originalPath == null) return; // nothing to delete in "add" mode
            new AlertDialog.Builder(this)
                    .setTitle("Delete endpoint?")
                    .setMessage(originalMethod + " " + originalPath)
                    .setPositiveButton("Delete", (d, w) -> {
                        ApiEndpointStore.delete(this, originalPath, originalMethod);
                        finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        setupLineNumberGutter();

        originalPath = getIntent().getStringExtra(EXTRA_EDIT_PATH);
        originalMethod = getIntent().getStringExtra(EXTRA_EDIT_METHOD);
        if (originalPath != null) {
            loadExisting(originalPath, originalMethod);
        } else {
            deleteButton.setVisibility(View.GONE);
        }
    }

    private void loadExisting(String path, String method) {
        ApiEndpointConfig config = ApiEndpointStore.find(this, path, method);
        if (config == null) return;

        endpointInput.setText(config.path);
        int methodIndex = 0;
        for (int i = 0; i < METHODS.length; i++) {
            if (METHODS[i].equalsIgnoreCase(config.method)) {
                methodIndex = i;
                break;
            }
        }
        methodSpinner.setSelection(methodIndex);

        for (KeyValue kv : config.headers) addRow(headersContainer, kv.key, kv.value);
        for (KeyValue kv : config.params) addRow(paramsContainer, kv.key, kv.value);

        bodyInput.setText(config.body);
    }

    private void addRow(android.widget.LinearLayout container, String key, String value) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_key_value_row, container, false);
        EditText keyInput = row.findViewById(R.id.row_key_input);
        EditText valueInput = row.findViewById(R.id.row_value_input);
        keyInput.setText(key);
        valueInput.setText(value);
        row.findViewById(R.id.row_delete_button).setOnClickListener(v -> container.removeView(row));
        container.addView(row);
    }

    private List<KeyValue> collectRows(android.widget.LinearLayout container) {
        List<KeyValue> result = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View row = container.getChildAt(i);
            EditText keyInput = row.findViewById(R.id.row_key_input);
            EditText valueInput = row.findViewById(R.id.row_value_input);
            String key = keyInput.getText().toString().trim();
            if (key.isEmpty()) continue;
            result.add(new KeyValue(key, valueInput.getText().toString()));
        }
        return result;
    }

    private void setupLineNumberGutter() {
        bodyInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                bodyInput.post(() -> updateLineNumbers());
            }
        });
        bodyInput.post(this::updateLineNumbers);

        bodyInput.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                lineNumbers.scrollTo(0, scrollY));
    }

    private void updateLineNumbers() {
        int lineCount = Math.max(bodyInput.getLineCount(), 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lineCount; i++) {
            sb.append(i);
            if (i < lineCount) sb.append("\n");
        }
        lineNumbers.setText(sb.toString());
    }

    private void save() {
        String rawPath = endpointInput.getText().toString().trim();
        if (rawPath.isEmpty()) {
            Toast.makeText(this, "Enter an endpoint path", Toast.LENGTH_SHORT).show();
            return;
        }
        String cleaned = rawPath.replaceAll("^/+", "").replaceAll("^api/+", "");
        String fullPath = "/api/" + cleaned;

        String method = METHODS[methodSpinner.getSelectedItemPosition()];

        String body = bodyInput.getText().toString().trim();
        if (body.isEmpty()) body = "{}";
        if (!isValidJson(body)) {
            Toast.makeText(this, "Body isn't valid JSON - saving anyway, but requests will return it as-is", Toast.LENGTH_LONG).show();
        }

        ApiEndpointConfig config = new ApiEndpointConfig();
        config.path = fullPath;
        config.method = method;
        config.headers = collectRows(headersContainer);
        config.params = collectRows(paramsContainer);
        config.body = body;

        ApiEndpointStore.save(this, config, originalPath, originalMethod);
        Toast.makeText(this, "Saved " + method + " " + fullPath, Toast.LENGTH_SHORT).show();
        finish();
    }

    private boolean isValidJson(String text) {
        try {
            if (text.startsWith("[")) {
                new JSONArray(text);
            } else {
                new JSONObject(text);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

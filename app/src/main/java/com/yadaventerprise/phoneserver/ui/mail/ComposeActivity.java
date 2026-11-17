package com.yadaventerprise.phoneserver.ui.mail;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.mail.MailAccount;
import com.yadaventerprise.phoneserver.mail.MailCompose;
import com.yadaventerprise.phoneserver.mail.MailService;
import com.yadaventerprise.phoneserver.security.PathGuard;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ComposeActivity extends AppCompatActivity {

    private static final int PICK_ATTACHMENT = 4101;

    private MailService service;
    private Spinner accountSpinner;
    private EditText to;
    private EditText cc;
    private EditText subject;
    private EditText body;
    private LinearLayout attachmentList;
    private TextView status;
    private List<MailAccount> accounts = new ArrayList<>();
    private final List<MailCompose.OutgoingAttachment> attachments = new ArrayList<>();
    private boolean loadingAccounts;
    private String requestedAccountId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        service = new MailService(this);
        requestedAccountId = getStringExtra(MailActivity.EXTRA_ACCOUNT_ID);

        LinearLayout root = MailUi.root(this);
        root.addView(MailUi.title(this, "Compose"), MailUi.matchWrap(this));
        root.addView(MailUi.label(this, "Account"), MailUi.matchWrap(this));
        accountSpinner = MailUi.spinner(this, new ArrayList<>());
        root.addView(accountSpinner, MailUi.matchWrap(this));

        to = field(root, "To");
        cc = field(root, "Cc (optional)");
        subject = field(root, "Subject");
        root.addView(MailUi.label(this, "Message"), MailUi.matchWrap(this));
        body = new EditText(this);
        body.setHint("Write your message");
        body.setMinLines(8);
        body.setGravity(android.view.Gravity.TOP);
        root.addView(body, MailUi.matchWrap(this));

        root.addView(MailUi.button(this, "Add attachment", v -> pickAttachment()), MailUi.matchWrap(this));
        attachmentList = new LinearLayout(this);
        attachmentList.setOrientation(LinearLayout.VERTICAL);
        root.addView(attachmentList, MailUi.matchWrap(this));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(MailUi.button(this, "Send", v -> send()), weight());
        actions.addView(MailUi.button(this, "Save draft", v -> saveDraft()), weight());
        root.addView(actions, MailUi.matchWrap(this));

        Button back = MailUi.button(this, "Back", v -> finish());
        root.addView(back, MailUi.matchWrap(this));
        status = MailUi.text(this, "");
        root.addView(status, MailUi.matchWrap(this));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        accountSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAccounts();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ATTACHMENT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            String name = displayName(uri);
            if (name == null || name.trim().isEmpty()) name = "attachment";
            byte[] bytes = read(uri);
            attachments.add(new MailCompose.OutgoingAttachment(
                    PathGuard.sanitizeFileName(name), "*/*".equals(contentType(uri))
                    ? "application/octet-stream" : contentType(uri), bytes));
            renderAttachments();
        } catch (Exception e) {
            status.setText("Attachment could not be read.");
        }
    }

    private void loadAccounts() {
        MailUi.run(this, status, () -> accounts = service.accounts(), () -> {
            List<String> names = new ArrayList<>();
            for (MailAccount account : accounts) {
                names.add(account.name.isEmpty() ? account.email : account.name);
            }
            loadingAccounts = true;
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, names);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            accountSpinner.setAdapter(adapter);
            for (int i = 0; i < accounts.size(); i++) {
                if (accounts.get(i).id.equals(requestedAccountId)) {
                    accountSpinner.setSelection(i);
                    break;
                }
            }
            loadingAccounts = false;
            if (accounts.isEmpty()) status.setText("Add a mail account before composing.");
        });
    }

    private void send() {
        String accountId = selectedAccountId();
        if (accountId == null) {
            status.setText("Add a mail account first.");
            return;
        }
        MailCompose compose = compose();
        MailUi.run(this, status, () -> service.send(accountId, compose), this::finish);
    }

    private void saveDraft() {
        String accountId = selectedAccountId();
        if (accountId == null) {
            status.setText("Add a mail account first.");
            return;
        }
        MailCompose compose = compose();
        MailUi.run(this, status, () -> service.saveDraft(accountId, compose), this::finish);
    }

    private MailCompose compose() {
        MailCompose compose = new MailCompose();
        compose.to = value(to);
        compose.cc = value(cc);
        compose.subject = value(subject);
        compose.text = body.getText().toString();
        compose.attachments.addAll(attachments);
        return compose;
    }

    private void pickAttachment() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_ATTACHMENT);
    }

    private void renderAttachments() {
        attachmentList.removeAllViews();
        int index = 0;
        for (MailCompose.OutgoingAttachment attachment : attachments) {
            final int position = index++;
            TextView row = MailUi.text(this, attachment.name + "  ·  " + attachment.bytes.length + " bytes");
            row.setOnClickListener(v -> {
                attachments.remove(position);
                renderAttachments();
            });
            attachmentList.addView(row, MailUi.matchWrap(this));
        }
    }

    private byte[] read(Uri uri) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("attachment unavailable");
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > 10_000_000) throw new IllegalStateException("attachment is too large");
                out.write(buffer, 0, read);
            }
        }
        return out.toByteArray();
    }

    private String displayName(Uri uri) {
        String name = null;
        android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && cursor.moveToFirst()) name = cursor.getString(column);
            } finally {
                cursor.close();
            }
        }
        if (name == null || name.trim().isEmpty()) name = uri.getLastPathSegment();
        return name == null ? "attachment" : name;
    }

    private String contentType(Uri uri) {
        String type = getContentResolver().getType(uri);
        return type == null || type.trim().isEmpty() ? "application/octet-stream" : type;
    }

    private String selectedAccountId() {
        int position = accountSpinner.getSelectedItemPosition();
        if (loadingAccounts || position < 0 || position >= accounts.size()) return null;
        return accounts.get(position).id;
    }

    private EditText field(LinearLayout parent, String hint) {
        EditText edit = MailUi.edit(this, hint);
        parent.addView(edit, MailUi.matchWrap(this));
        return edit;
    }

    private String getStringExtra(String key) {
        String value = getIntent().getStringExtra(key);
        return value == null ? "" : value;
    }

    private static String value(EditText field) {
        return field.getText().toString().trim();
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }
}

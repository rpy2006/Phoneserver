package com.yadaventerprise.phoneserver.ui.mail;

import android.os.Bundle;
import android.text.InputType;
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
import com.yadaventerprise.phoneserver.mail.MailService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

public class MailAccountsActivity extends AppCompatActivity {

    private MailService service;
    private TextView status;
    private LinearLayout accountsList;
    private EditText name;
    private EditText email;
    private EditText username;
    private EditText password;
    private EditText smtpHost;
    private EditText smtpPort;
    private Spinner smtpSecurity;
    private EditText imapHost;
    private EditText imapPort;
    private Spinner imapSecurity;
    private EditText fromName;
    private EditText sentFolder;
    private EditText draftsFolder;
    private String editingId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        service = new MailService(this);
        LinearLayout root = MailUi.root(this);
        root.addView(MailUi.title(this, "Mail Accounts"), MailUi.matchWrap(this));

        name = field(root, "Account name");
        email = field(root, "Email address");
        username = field(root, "Login username");
        password = field(root, "Password / app password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fromName = field(root, "Display name (optional)");

        root.addView(MailUi.label(this, "SMTP host and port"), MailUi.matchWrap(this));
        LinearLayout smtp = row();
        smtpHost = MailUi.edit(this, "smtp.example.com");
        smtpPort = MailUi.edit(this, "587");
        smtpPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        smtpSecurity = MailUi.spinner(this, Arrays.asList("starttls", "tls"));
        smtp.addView(smtpHost, weight());
        smtp.addView(smtpPort, small());
        smtp.addView(smtpSecurity, small());
        root.addView(smtp, MailUi.matchWrap(this));

        root.addView(MailUi.label(this, "IMAP host and port"), MailUi.matchWrap(this));
        LinearLayout imap = row();
        imapHost = MailUi.edit(this, "imap.example.com");
        imapPort = MailUi.edit(this, "993");
        imapPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        imapSecurity = MailUi.spinner(this, Arrays.asList("tls", "starttls"));
        imap.addView(imapHost, weight());
        imap.addView(imapPort, small());
        imap.addView(imapSecurity, small());
        root.addView(imap, MailUi.matchWrap(this));

        sentFolder = field(root, "Sent folder");
        sentFolder.setText("Sent");
        draftsFolder = field(root, "Drafts folder");
        draftsFolder.setText("Drafts");

        LinearLayout actions = row();
        actions.addView(MailUi.button(this, "Save", v -> save()), weight());
        actions.addView(MailUi.button(this, "Test", v -> test()), weight());
        actions.addView(MailUi.button(this, "Delete", v -> delete()), weight());
        root.addView(actions, MailUi.matchWrap(this));

        root.addView(MailUi.button(this, "New account", v -> clearForm()), MailUi.matchWrap(this));
        Button back = MailUi.button(this, "Back", v -> finish());
        root.addView(back, MailUi.matchWrap(this));

        status = MailUi.text(this, "");
        root.addView(status, MailUi.matchWrap(this));
        accountsList = new LinearLayout(this);
        accountsList.setOrientation(LinearLayout.VERTICAL);
        root.addView(accountsList, MailUi.matchWrap(this));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        MailUi.run(this, status, () -> {
            final List<MailAccount> loaded = service.accounts();
            runOnUiThread(() -> renderAccounts(loaded));
        }, null);
    }

    private void renderAccounts(List<MailAccount> loaded) {
        accountsList.removeAllViews();
        if (loaded.isEmpty()) {
            accountsList.addView(MailUi.text(this, "No saved accounts."));
            return;
        }
        for (MailAccount account : loaded) {
            TextView row = MailUi.text(this, (account.name.isEmpty() ? account.email : account.name)
                    + "\n" + account.email + "  ·  SMTP " + account.smtpHost + ":" + account.smtpPort
                    + "  ·  IMAP " + account.imapHost + ":" + account.imapPort);
            row.setOnClickListener(v -> fill(account));
            accountsList.addView(row, MailUi.matchWrap(this));
        }
    }

    private void save() {
        JSONObject body;
        try {
            body = body();
        } catch (Exception e) {
            status.setText("Account details could not be read.");
            return;
        }
        MailUi.run(this, status, () -> {
            MailAccount saved = service.saveAccount(body);
            runOnUiThread(() -> {
                editingId = saved.id;
                password.setText("");
            });
        }, this::load);
    }

    private void test() {
        if (editingId.isEmpty()) {
            status.setText("Save the account before testing it.");
            return;
        }
        String id = editingId;
        MailUi.run(this, status, () -> service.test(id), null);
    }

    private void delete() {
        if (editingId.isEmpty()) {
            status.setText("Select a saved account first.");
            return;
        }
        String id = editingId;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete mail account")
                .setMessage("Delete this account and all cached messages for it?")
                .setPositiveButton("Delete", (dialog, which) -> MailUi.run(this, status,
                        () -> service.deleteAccount(id), this::clearFormAndLoad))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearFormAndLoad() {
        clearForm();
        load();
    }

    private void clearForm() {
        editingId = "";
        for (EditText field : Arrays.asList(name, email, username, password, smtpHost, smtpPort,
                imapHost, imapPort, fromName)) {
            field.setText("");
        }
        smtpPort.setText("587");
        imapPort.setText("993");
        smtpSecurity.setSelection(0);
        imapSecurity.setSelection(0);
        sentFolder.setText("Sent");
        draftsFolder.setText("Drafts");
        status.setText("New account form.");
    }

    private void fill(MailAccount account) {
        editingId = account.id;
        name.setText(account.name);
        email.setText(account.email);
        username.setText(account.username);
        password.setText("");
        smtpHost.setText(account.smtpHost);
        smtpPort.setText(String.valueOf(account.smtpPort));
        setSecurity(smtpSecurity, account.smtpSecurity);
        imapHost.setText(account.imapHost);
        imapPort.setText(String.valueOf(account.imapPort));
        setSecurity(imapSecurity, account.imapSecurity);
        fromName.setText(account.fromName);
        sentFolder.setText(account.sentFolder);
        draftsFolder.setText(account.draftsFolder);
        status.setText("Editing " + account.email + ". Leave password blank to keep the saved one.");
    }

    private JSONObject body() throws Exception {
        JSONObject json = new JSONObject();
        if (!editingId.isEmpty()) json.put("id", editingId);
        json.put("name", value(name));
        json.put("email", value(email));
        json.put("username", value(username));
        if (!value(password).isEmpty()) json.put("password", value(password));
        json.put("smtpHost", value(smtpHost));
        json.put("smtpPort", integer(smtpPort));
        json.put("smtpSecurity", smtpSecurity.getSelectedItem().toString());
        json.put("imapHost", value(imapHost));
        json.put("imapPort", integer(imapPort));
        json.put("imapSecurity", imapSecurity.getSelectedItem().toString());
        json.put("fromName", value(fromName));
        json.put("sentFolder", value(sentFolder));
        json.put("draftsFolder", value(draftsFolder));
        return json;
    }

    private EditText field(LinearLayout parent, String hint) {
        EditText edit = MailUi.edit(this, hint);
        parent.addView(edit, MailUi.matchWrap(this));
        return edit;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private void setSecurity(Spinner spinner, String value) {
        String mode = "tls".equalsIgnoreCase(value) ? "tls" : "starttls";
        ArrayAdapter<?> adapter = (ArrayAdapter<?>) spinner.getAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (mode.equals(String.valueOf(adapter.getItem(i)))) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private static String value(EditText field) {
        return field.getText().toString().trim();
    }

    private static int integer(EditText field) {
        try {
            return Integer.parseInt(value(field));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams small() {
        return new LinearLayout.LayoutParams(MailUi.dp(this, 90), ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}

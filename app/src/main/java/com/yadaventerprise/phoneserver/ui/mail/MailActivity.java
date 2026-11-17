package com.yadaventerprise.phoneserver.ui.mail;

import android.content.Intent;
import android.os.Bundle;
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
import com.yadaventerprise.phoneserver.mail.MailMessage;
import com.yadaventerprise.phoneserver.mail.MailService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MailActivity extends AppCompatActivity {

    public static final String EXTRA_ACCOUNT_ID = "account_id";

    private MailService service;
    private Spinner accountSpinner;
    private Spinner folderSpinner;
    private EditText searchField;
    private LinearLayout list;
    private TextView status;
    private List<MailAccount> accounts = new ArrayList<>();
    private List<MailMessage> messages = new ArrayList<>();
    private boolean loadingAccounts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        service = new MailService(this);

        LinearLayout root = MailUi.root(this);
        root.addView(MailUi.title(this, "Mail"));
        root.addView(MailUi.label(this, "Account"), MailUi.matchWrap(this));

        accountSpinner = MailUi.spinner(this, new ArrayList<>());
        root.addView(accountSpinner, MailUi.matchWrap(this));

        LinearLayout folders = new LinearLayout(this);
        folders.setOrientation(LinearLayout.HORIZONTAL);
        folderSpinner = MailUi.spinner(this, Arrays.asList("INBOX", "SENT", "DRAFTS"));
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        folders.addView(folderSpinner, spinnerParams);
        folders.addView(MailUi.button(this, "Sync", v -> sync()), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(folders, MailUi.matchWrap(this));

        searchField = MailUi.edit(this, "Search subject, sender, or body");
        root.addView(searchField, MailUi.matchWrap(this));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(MailUi.button(this, "Search", v -> loadMessages()), weight());
        actions.addView(MailUi.button(this, "Compose", v -> openCompose()), weight());
        actions.addView(MailUi.button(this, "Accounts", v ->
                startActivity(new Intent(this, MailAccountsActivity.class))), weight());
        root.addView(actions, MailUi.matchWrap(this));

        status = MailUi.text(this, "");
        root.addView(status, MailUi.matchWrap(this));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, MailUi.matchWrap(this));

        Button back = MailUi.button(this, "Back", v -> finish());
        root.addView(back, MailUi.matchWrap(this));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        accountSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (!loadingAccounts) loadMessages();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        folderSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (!loadingAccounts) loadMessages();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        String requested = getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
        if (requested != null && !requested.trim().isEmpty()) {
            getIntent().removeExtra(EXTRA_ACCOUNT_ID);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAccounts();
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
            loadingAccounts = false;
            if (accounts.isEmpty()) {
                status.setText("No mail accounts yet. Add one with Accounts.");
                list.removeAllViews();
                list.addView(MailUi.text(this, "Outgoing SMTP and incoming IMAP are configured per account."));
            } else {
                loadMessages();
            }
        });
    }

    private void loadMessages() {
        String accountId = selectedAccountId();
        if (accountId == null) {
            status.setText("Select or add a mail account first.");
            list.removeAllViews();
            return;
        }
        String query = searchField.getText().toString().trim();
        String folder = (String) folderSpinner.getSelectedItem();
        MailUi.run(this, status, () -> messages = query.isEmpty()
                ? service.messages(accountId, folder)
                : service.search(accountId, query), this::renderMessages);
    }

    private void sync() {
        String accountId = selectedAccountId();
        if (accountId == null) {
            status.setText("Add a mail account first.");
            return;
        }
        MailUi.run(this, status, () -> service.sync(accountId, 50), this::loadMessages);
    }

    private void renderMessages() {
        list.removeAllViews();
        if (messages.isEmpty()) {
            list.addView(MailUi.text(this, "No messages in this view."));
            return;
        }
        for (MailMessage message : messages) {
            TextView row = MailUi.text(this, (message.seen ? "" : "NEW  ")
                    + (message.folder.isEmpty() ? "" : message.folder + "  ·  ")
                    + (message.fromName.isEmpty() ? message.fromAddress : message.fromName)
                    + "\n" + (message.subject.isEmpty() ? "(no subject)" : message.subject)
                    + "\n" + message.snippet());
            row.setPadding(MailUi.dp(this, 8), MailUi.dp(this, 10), MailUi.dp(this, 8), MailUi.dp(this, 10));
            row.setOnClickListener(v -> {
                Intent intent = new Intent(this, MessageDetailActivity.class);
                intent.putExtra(MessageDetailActivity.EXTRA_MESSAGE_ID, message.id);
                startActivity(intent);
            });
            list.addView(row, MailUi.matchWrap(this));
        }
    }

    private void openCompose() {
        String accountId = selectedAccountId();
        if (accountId == null) {
            status.setText("Add a mail account first.");
            return;
        }
        Intent intent = new Intent(this, ComposeActivity.class);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        startActivity(intent);
    }

    private String selectedAccountId() {
        int position = accountSpinner.getSelectedItemPosition();
        if (position < 0 || position >= accounts.size()) return null;
        return accounts.get(position).id;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }
}

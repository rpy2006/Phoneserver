package com.yadaventerprise.phoneserver.ui.mail;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.yadaventerprise.phoneserver.mail.MailAttachmentRef;
import com.yadaventerprise.phoneserver.mail.MailHtmlSanitizer;
import com.yadaventerprise.phoneserver.mail.MailMessage;
import com.yadaventerprise.phoneserver.mail.MailService;
import java.util.List;

public class MessageDetailActivity extends AppCompatActivity {

    public static final String EXTRA_MESSAGE_ID = "message_id";

    private MailService service;
    private TextView status;
    private LinearLayout content;
    private String messageId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        service = new MailService(this);
        messageId = getIntent().getStringExtra(EXTRA_MESSAGE_ID);

        LinearLayout root = MailUi.root(this);
        root.addView(MailUi.title(this, "Message"), MailUi.matchWrap(this));
        status = MailUi.text(this, "Loading...");
        root.addView(status, MailUi.matchWrap(this));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, MailUi.matchWrap(this));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(MailUi.button(this, "Delete local copy", v -> deleteLocal()), weight());
        actions.addView(MailUi.button(this, "Back", v -> finish()), weight());
        root.addView(actions, MailUi.matchWrap(this));

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
        if (messageId == null || messageId.isEmpty()) {
            status.setText("No message selected.");
            return;
        }
        MailUi.run(this, status, () -> {
            MailMessage message = service.message(messageId);
            if (message == null) throw new IllegalStateException("message was not found");
            if (!message.seen) service.markRead(messageId, true);
            final MailMessage loaded = message;
            runOnUiThread(() -> render(loaded));
        }, null);
    }

    private void render(MailMessage message) {
        content.removeAllViews();
        content.addView(MailUi.text(this, message.subject.isEmpty() ? "(no subject)" : message.subject));
        String sender = message.fromName.isEmpty() ? message.fromAddress : message.fromName + " <" + message.fromAddress + ">";
        content.addView(MailUi.text(this, "From: " + sender));
        content.addView(MailUi.text(this, "To: " + message.to));
        content.addView(MailUi.text(this, "Folder: " + message.folder + "  ·  " + message.dateIso));

        if (!message.text.trim().isEmpty()) {
            content.addView(MailUi.text(this, message.text));
        }
        if (!message.html.trim().isEmpty()) {
            String safe = MailHtmlSanitizer.sanitize(message.html);
            TextView html = MailUi.text(this, "");
            html.setText(Html.fromHtml(safe, Html.FROM_HTML_MODE_LEGACY));
            content.addView(html, MailUi.matchWrap(this));
        }

        List<MailAttachmentRef> files = message.attachments;
        if (!files.isEmpty()) {
            content.addView(MailUi.label(this, "Attachments"));
            for (int i = 0; i < files.size(); i++) {
                final int index = i;
                MailAttachmentRef ref = files.get(i);
                TextView row = MailUi.text(this, ref.name + "  ·  " + ref.size + " bytes");
                row.setOnClickListener(v -> openAttachment(index));
                content.addView(row, MailUi.matchWrap(this));
            }
        }
        status.setText("");
    }

    private void openAttachment(int index) {
        String id = messageId;
        MailUi.run(this, status, () -> {
            MailService.AttachmentFile file = service.attachment(id, index);
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file.file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, file.contentType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            runOnUiThread(() -> startActivity(intent));
        }, null);
    }

    private void deleteLocal() {
        String id = messageId;
        MailUi.run(this, status, () -> service.deleteLocal(id), this::finish);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }
}

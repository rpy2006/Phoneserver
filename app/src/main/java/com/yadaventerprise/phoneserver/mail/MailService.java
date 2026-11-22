package com.yadaventerprise.phoneserver.mail;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

public final class MailService {

    public static final class AttachmentFile {
        public final File file;
        public final String name;
        public final String contentType;

        AttachmentFile(File file, String name, String contentType) {
            this.file = file;
            this.name = name;
            this.contentType = contentType;
        }
    }

    private final Context context;
    private final MailStore store;

    public MailService(Context context) {
        this.context = context.getApplicationContext();
        this.store = new MailStore(this.context);
    }

    public MailStore store() {
        return store;
    }

    public List<MailAccount> accounts() throws MailException {
        return store.listAccounts();
    }

    public MailAccount account(String id) throws MailException {
        return store.getAccount(id);
    }

    public MailAccount saveAccount(JSONObject input) throws MailException {
        return saveAccount(input, true);
    }

    public MailAccount saveAccount(JSONObject input, boolean verify) throws MailException {
        return saveAccountForOwner(input, verify, "");
    }

    public MailAccount saveAccountForOwner(JSONObject input, boolean verify, String ownerId) throws MailException {
        if (input == null) throw new MailException("mail account details are required");
        MailAccount account = new MailAccount();
        String id = input.optString("id", "");
        if (!id.trim().isEmpty()) {
            MailAccount existing = store.getAccount(id);
            if (existing == null) throw new MailException("mail account was not found", 404);
            account = existing;
        } else {
            account.id = MailStore.newId();
            account.ownerId = ownerId == null ? "" : ownerId;
        }
        if (input.has("name")) account.name = input.optString("name", "").trim();
        if (input.has("email")) account.email = input.optString("email", "").trim().toLowerCase(Locale.US);
        if (input.has("username")) account.username = input.optString("username", "").trim();
        if (input.has("smtpHost")) account.smtpHost = input.optString("smtpHost", "").trim();
        if (input.has("imapHost")) account.imapHost = input.optString("imapHost", "").trim();
        if (input.has("smtpPort")) account.smtpPort = input.optInt("smtpPort", account.smtpPort);
        if (input.has("imapPort")) account.imapPort = input.optInt("imapPort", account.imapPort);
        if (input.has("smtpSecurity")) account.smtpSecurity = input.optString("smtpSecurity", account.smtpSecurity);
        if (input.has("imapSecurity")) account.imapSecurity = input.optString("imapSecurity", account.imapSecurity);
        if (input.has("fromName")) account.fromName = input.optString("fromName", "").trim();
        if (input.has("sentFolder")) account.sentFolder = input.optString("sentFolder", account.sentFolder).trim();
        if (input.has("draftsFolder")) account.draftsFolder = input.optString("draftsFolder", account.draftsFolder).trim();
        if ((account.ownerId == null || account.ownerId.isEmpty()) && ownerId != null && !ownerId.isEmpty()) account.ownerId = ownerId;
        if (input.has("password") && !input.optString("password", "").isEmpty()) {
            account.passwordCipher = MailSecretStore.encrypt(context, account.id, input.optString("password"));
        }
        MailValidation.validate(account);
        if (account.passwordCipher.isEmpty()) throw new MailException("a password is required");
        if (verify) verifyAccount(account);
        store.saveAccount(account);
        return account;
    }

    public void deleteAccount(String id) throws MailException {
        store.deleteAccount(id);
    }

    public void test(String id) throws MailException {
        verifyAccount(require(id));
    }

    private void verifyAccount(MailAccount account) throws MailException {
        String password = password(account);
        SmtpClient.verify(account, account.username, password);
        ImapClient imap = ImapClient.connect(account, account.username, password);
        imap.close();
    }

    public int sync(String id, int limit) throws MailException {
        MailAccount account = require(id);
        String password = password(account);
        int max = limit <= 0 ? 50 : Math.min(limit, 200);
        ImapClient imap = ImapClient.connect(account, account.username, password);
        try {
            imap.select("INBOX");
            List<String> uids = imap.uids();
            if (uids.size() > max) uids = new ArrayList<>(uids.subList(uids.size() - max, uids.size()));
            List<MailMessage> messages = new ArrayList<>();
            for (String uid : uids) {
                ImapClient.Fetched fetched = imap.fetch(uid);
                MailMime.Parsed parsed = MailMime.parse(fetched.message);
                String localId = messageId(account.id, "INBOX", uid);
                MailMessage existing = store.getMessage(localId);
                messages.add(toMessage(account, localId, uid, parsed, fetched, existing));
            }
            store.upsertMessages(messages);
            return messages.size();
        } finally {
            imap.close();
        }
    }

    public List<MailMessage> messages(String id, String folder) throws MailException {
        if (folder == null || folder.trim().isEmpty()) folder = "INBOX";
        return store.listMessages(id, folder);
    }

    public List<MailMessage> search(String id, String query) throws MailException {
        String term = query == null ? "" : query.trim().toLowerCase(Locale.US);
        List<MailMessage> all = store.listMessages(id, null);
        List<MailMessage> result = new ArrayList<>();
        if (term.isEmpty()) return all;
        for (MailMessage message : all) {
            if (message.subject.toLowerCase(Locale.US).contains(term)
                    || message.fromAddress.toLowerCase(Locale.US).contains(term)
                    || message.fromName.toLowerCase(Locale.US).contains(term)
                    || message.text.toLowerCase(Locale.US).contains(term)) {
                result.add(message);
            }
        }
        return result;
    }

    public MailMessage message(String messageId) throws MailException {
        return store.getMessage(messageId);
    }

    public void markRead(String messageId, boolean seen) throws MailException {
        MailMessage message = store.getMessage(messageId);
        if (message == null) throw new MailException("message was not found", 404);
        store.markSeen(messageId, seen);
        if (!seen || message.uid.isEmpty() || !MailValidation.isFolderName(message.folder)) return;
        MailAccount account = store.getAccount(message.accountId);
        if (account == null) return;
        ImapClient imap = ImapClient.connect(account, account.username, password(account));
        try {
            imap.select(message.folder);
            imap.markSeen(message.uid);
        } finally {
            imap.close();
        }
    }

    public MailMessage send(String id, MailCompose compose) throws MailException {
        MailAccount account = require(id);
        compose.validate();
        String password = password(account);
        byte[] raw = MailMime.build(account, compose);
        List<String> recipients = new ArrayList<>();
        appendAddresses(recipients, compose.to);
        appendAddresses(recipients, compose.cc);
        appendAddresses(recipients, compose.bcc);
        SmtpClient.send(account, account.username, password, recipients, raw);
        MailMessage sent = localSent(account, compose, raw);
        List<MailMessage> list = new ArrayList<>();
        list.add(sent);
        store.upsertMessages(list);
        try {
            ImapClient imap = ImapClient.connect(account, account.username, password);
            try {
                imap.append(account.sentFolder, raw);
            } finally {
                imap.close();
            }
        } catch (MailException ignored) {
        }
        return sent;
    }

    public MailMessage saveDraft(String id, MailCompose compose) throws MailException {
        MailAccount account = require(id);
        MailMessage draft = new MailMessage();
        draft.id = MailStore.newId();
        draft.accountId = account.id;
        draft.folder = "DRAFTS";
        draft.uid = "";
        draft.fromAddress = account.email;
        draft.fromName = account.fromName;
        draft.to = compose.to;
        draft.cc = compose.cc;
        draft.subject = compose.subject;
        draft.text = compose.text;
        draft.html = compose.html;
        draft.dateMillis = System.currentTimeMillis();
        draft.dateIso = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
                .format(new java.util.Date(draft.dateMillis));
        List<MailMessage> list = new ArrayList<>();
        list.add(draft);
        store.upsertMessages(list);
        return draft;
    }

    public void deleteLocal(String messageId) throws MailException {
        store.deleteMessage(messageId);
    }

    public AttachmentFile attachment(String messageId, int index) throws MailException {
        MailMessage message = store.getMessage(messageId);
        if (message == null) throw new MailException("message was not found", 404);
        if (index < 0 || index >= message.attachments.size()) throw new MailException("attachment was not found", 404);
        MailAttachmentRef ref = message.attachments.get(index);
        File source = store.getAttachmentFile(message.accountId, message.uid, ref.storageKey);
        File folder = new File(context.getCacheDir(), "mail-attachments");
        if (!folder.exists() && !folder.mkdirs()) throw new MailException("attachment cache is unavailable", 500);
        File target = new File(folder, ref.storageKey + "/" + ref.name);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new MailException("attachment cache is unavailable", 500);
        try (java.io.FileInputStream in = new java.io.FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            throw new MailException("attachment could not be prepared", 500);
        }
        return new AttachmentFile(target, ref.name, ref.contentType);
    }

    private MailAccount require(String id) throws MailException {
        MailAccount account = store.getAccount(id);
        if (account == null) throw new MailException("mail account was not found", 404);
        return account;
    }

    private String password(MailAccount account) throws MailException {
        String value = MailSecretStore.decrypt(context, account.id, account.passwordCipher);
        if (value.isEmpty()) throw new MailException("mail account password is missing");
        return value;
    }

    private MailMessage toMessage(MailAccount account, String localId, String uid, MailMime.Parsed parsed,
                                  ImapClient.Fetched fetched, MailMessage existing) throws MailException {
        MailMessage message = existing == null ? new MailMessage() : existing;
        message.id = localId;
        message.accountId = account.id;
        message.folder = "INBOX";
        message.uid = uid;
        message.fromAddress = addressOf(parsed.from);
        message.fromName = nameOf(parsed.from);
        message.to = parsed.to;
        message.cc = parsed.cc;
        message.subject = parsed.subject;
        message.dateIso = parsed.date;
        message.dateMillis = dateMillis(parsed.date);
        message.text = parsed.text;
        message.html = MailHtmlSanitizer.sanitize(parsed.html);
        message.seen = fetched.seen || (existing != null && existing.seen);
        message.flagged = fetched.flagged || (existing != null && existing.flagged);
        if ((existing == null || existing.attachments.isEmpty()) && !parsed.attachments.isEmpty()) {
            message.attachments.addAll(store.saveAttachments(account.id, uid, parsed.attachments));
        }
        return message;
    }

    private MailMessage localSent(MailAccount account, MailCompose compose, byte[] raw) throws MailException {
        MailMime.Parsed parsed = MailMime.parse(raw);
        MailMessage message = new MailMessage();
        message.id = MailStore.newId();
        message.accountId = account.id;
        message.folder = "SENT";
        message.uid = "";
        message.fromAddress = account.email;
        message.fromName = account.fromName;
        message.to = compose.to;
        message.cc = compose.cc;
        message.subject = compose.subject;
        message.dateMillis = System.currentTimeMillis();
        message.dateIso = parsed.date;
        message.text = compose.text;
        message.html = MailHtmlSanitizer.sanitize(compose.html);
        message.seen = true;
        return message;
    }

    private static String messageId(String accountId, String folder, String uid) {
        String cleanFolder = folder.replaceAll("[^A-Za-z0-9_-]", "_");
        return "mail_" + accountId + "_" + cleanFolder + "_" + uid;
    }

    private static void appendAddresses(List<String> target, String value) {
        for (String address : value.split("[,;\\s]+")) {
            if (!address.trim().isEmpty()) target.add(address.trim());
        }
    }

    private static String addressOf(String from) {
        int lt = from.lastIndexOf('<');
        int gt = from.lastIndexOf('>');
        if (lt >= 0 && gt > lt) return from.substring(lt + 1, gt).trim();
        return from.trim();
    }

    private static String nameOf(String from) {
        int lt = from.indexOf('<');
        if (lt <= 0) return "";
        return MailValidation.cleanHeaderValue(from.substring(0, lt));
    }

    private static long dateMillis(String value) {
        if (value == null || value.trim().isEmpty()) return System.currentTimeMillis();
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss Z",
                "dd MMM yyyy HH:mm:ss Z",
                "EEE, d MMM yyyy HH:mm:ss Z"
        };
        for (String pattern : patterns) {
            try {
                return new SimpleDateFormat(pattern, Locale.US).parse(value.trim()).getTime();
            } catch (ParseException ignored) {
            }
        }
        return System.currentTimeMillis();
    }
}

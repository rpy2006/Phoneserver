package com.yadaventerprise.phoneserver.mail;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.JsonStorage;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import com.yadaventerprise.phoneserver.security.PathGuard;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MailStore {

    private final Context context;
    private final File dir;
    private final File dataFile;
    private final Object lock = new Object();

    public MailStore(Context context) {
        this.context = context.getApplicationContext();
        this.dir = new File(this.context.getFilesDir(), "mail");
        this.dataFile = new File(dir, "mail-data.json");
        SecurityPaths.restrict(dir);
    }

    public List<MailAccount> listAccounts() throws MailException {
        synchronized (lock) {
            JSONArray array = read().optJSONArray("accounts");
            List<MailAccount> result = new ArrayList<>();
            if (array == null) return result;
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row != null) result.add(MailAccount.fromJson(row));
            }
            return result;
        }
    }

    public MailAccount getAccount(String id) throws MailException {
        if (id == null || id.trim().isEmpty()) return null;
        synchronized (lock) {
            JSONArray array = read().optJSONArray("accounts");
            if (array == null) return null;
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row != null && id.equals(row.optString("id"))) return MailAccount.fromJson(row);
            }
            return null;
        }
    }

    public void saveAccount(MailAccount account) throws MailException {
        if (account == null) throw new MailException("mail account is required");
        if (account.id == null || account.id.trim().isEmpty()) account.id = newId();
        account.updatedAt = System.currentTimeMillis();
        synchronized (lock) {
            JSONObject root = read();
            JSONArray accounts = array(root, "accounts");
            boolean replaced = false;
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject row = accounts.optJSONObject(i);
                if (row != null && account.id.equals(row.optString("id"))) {
                    put(accounts, i, stored(account));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) accounts.put(stored(account));
            write(root);
        }
    }

    public void deleteAccount(String id) throws MailException {
        if (id == null || id.trim().isEmpty()) return;
        synchronized (lock) {
            JSONObject root = read();
            JSONArray accounts = array(root, "accounts");
            JSONArray keptAccounts = new JSONArray();
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject row = accounts.optJSONObject(i);
                if (row == null || !id.equals(row.optString("id"))) keptAccounts.put(row);
            }
            put(root, "accounts", keptAccounts);
            JSONArray messages = array(root, "messages");
            JSONArray keptMessages = new JSONArray();
            for (int i = 0; i < messages.length(); i++) {
                JSONObject row = messages.optJSONObject(i);
                if (row == null || !id.equals(row.optString("accountId"))) keptMessages.put(row);
            }
            put(root, "messages", keptMessages);
            write(root);
        }
        MailSecretStore.delete(id);
        deleteRecursive(new File(accountDir(id), "attachments"));
    }

    public List<MailMessage> listMessages(String accountId, String folder) throws MailException {
        synchronized (lock) {
            JSONArray array = read().optJSONArray("messages");
            List<MailMessage> result = new ArrayList<>();
            if (array == null) return result;
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row == null) continue;
                if (accountId != null && !accountId.equals(row.optString("accountId"))) continue;
                if (folder != null && !folder.equals(row.optString("folder"))) continue;
                result.add(MailMessage.fromJson(row));
            }
            result.sort((a, b) -> Long.compare(b.dateMillis, a.dateMillis));
            return result;
        }
    }

    public MailMessage getMessage(String id) throws MailException {
        if (id == null || id.trim().isEmpty()) return null;
        synchronized (lock) {
            JSONArray array = read().optJSONArray("messages");
            if (array == null) return null;
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row != null && id.equals(row.optString("id"))) return MailMessage.fromJson(row);
            }
            return null;
        }
    }

    public void upsertMessages(List<MailMessage> messages) throws MailException {
        if (messages == null || messages.isEmpty()) return;
        synchronized (lock) {
            JSONObject root = read();
            JSONArray rows = array(root, "messages");
            for (MailMessage message : messages) {
                int match = -1;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null && sameMessage(row, message)) {
                        match = i;
                        break;
                    }
                }
                JSONObject stored = stored(message);
                if (match >= 0) put(rows, match, stored);
                else put(rows, stored);
            }
            trimMessages(rows);
            put(root, "messages", rows);
            write(root);
        }
    }

    public void markSeen(String id, boolean seen) throws MailException {
        synchronized (lock) {
            JSONObject root = read();
            JSONArray rows = array(root, "messages");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && id.equals(row.optString("id"))) {
                    put(row, "seen", seen);
                    break;
                }
            }
            write(root);
        }
    }

    public void deleteMessage(String id) throws MailException {
        synchronized (lock) {
            JSONObject root = read();
            JSONArray rows = array(root, "messages");
            JSONArray kept = new JSONArray();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null || !id.equals(row.optString("id"))) kept.put(row);
            }
            put(root, "messages", kept);
            write(root);
        }
    }

    public List<MailAttachmentRef> saveAttachments(String accountId, String uid,
                                                   List<MailMime.Attachment> attachments) throws MailException {
        List<MailAttachmentRef> refs = new ArrayList<>();
        if (attachments == null) return refs;
        File folder = new File(accountDir(accountId), "attachments/" + safeUid(uid));
        if (!folder.exists() && !folder.mkdirs()) throw new MailException("attachment storage is unavailable", 500);
        SecurityPaths.restrict(folder);
        int index = 0;
        for (MailMime.Attachment attachment : attachments) {
            String key = newId();
            File target = new File(folder, key);
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(attachment.bytes);
                out.flush();
                out.getFD().sync();
            } catch (IOException e) {
                throw new MailException("attachment could not be stored", 500);
            }
            MailAttachmentRef ref = new MailAttachmentRef();
            ref.index = index++;
            ref.name = PathGuard.sanitizeFileName(attachment.name);
            ref.contentType = attachment.contentType;
            ref.storageKey = key;
            ref.size = attachment.bytes.length;
            refs.add(ref);
        }
        return refs;
    }

    public File getAttachmentFile(String accountId, String uid, String key) throws MailException {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}")) throw new MailException("invalid attachment", 400);
        File file = new File(new File(accountDir(accountId), "attachments/" + safeUid(uid)), key);
        if (!file.exists() || !PathGuard.isInside(accountDir(accountId), file)) {
            throw new MailException("attachment is not available", 404);
        }
        return file;
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private JSONObject read() throws MailException {
        try {
            JSONObject root = JsonStorage.readJson(dataFile);
            if (!root.has("schema")) root.put("schema", 1);
            return root;
        } catch (Exception e) {
            throw new MailException("mail data could not be read", 500);
        }
    }

    private void write(JSONObject root) throws MailException {
        try {
            JsonStorage.writeJsonAtomic(dataFile, root, true);
            SecurityPaths.restrict(dataFile);
            SecurityPaths.restrict(dir);
        } catch (IOException e) {
            throw new MailException("mail data could not be saved", 500);
        }
    }

    private File accountDir(String accountId) {
        String clean = accountId == null ? "unknown" : accountId.replaceAll("[^A-Za-z0-9_-]", "");
        if (clean.length() > 64) clean = clean.substring(0, 64);
        return new File(dir, "accounts/" + clean);
    }

    private String safeUid(String uid) {
        return uid == null || !uid.matches("[0-9]{1,12}") ? "local" : uid;
    }

    private static void put(JSONObject object, String key, Object value) throws MailException {
        try {
            object.put(key, value);
        } catch (Exception e) {
            throw new MailException("mail data could not be encoded", 500);
        }
    }

    private static void put(JSONArray array, Object value) throws MailException {
        try {
            array.put(value);
        } catch (Exception e) {
            throw new MailException("mail data could not be encoded", 500);
        }
    }

    private static void put(JSONArray array, int index, Object value) throws MailException {
        try {
            array.put(index, value);
        } catch (Exception e) {
            throw new MailException("mail data could not be encoded", 500);
        }
    }

    private static JSONArray array(JSONObject root, String key) throws MailException {
        JSONArray value = root.optJSONArray(key);
        if (value != null) return value;
        value = new JSONArray();
        try {
            root.put(key, value);
        } catch (Exception e) {
            throw new MailException("mail data is invalid", 500);
        }
        return value;
    }

    private static JSONObject stored(MailAccount account) throws MailException {
        try {
            return account.toStoredJson();
        } catch (Exception e) {
            throw new MailException("mail account could not be encoded", 500);
        }
    }

    private static JSONObject stored(MailMessage message) throws MailException {
        try {
            JSONObject json = new JSONObject();
            json.put("id", message.id.isEmpty() ? newId() : message.id);
            json.put("accountId", message.accountId);
            json.put("folder", message.folder);
            json.put("uid", message.uid);
            json.put("fromAddress", message.fromAddress);
            json.put("fromName", message.fromName);
            json.put("to", message.to);
            json.put("cc", message.cc);
            json.put("subject", message.subject);
            json.put("date", message.dateIso);
            json.put("dateMillis", message.dateMillis);
            json.put("text", message.text);
            json.put("html", message.html);
            json.put("seen", message.seen);
            json.put("flagged", message.flagged);
            JSONArray files = new JSONArray();
            for (MailAttachmentRef ref : message.attachments) files.put(ref.toJson());
            json.put("attachments", files);
            return json;
        } catch (Exception e) {
            throw new MailException("message could not be encoded", 500);
        }
    }

    private static boolean sameMessage(JSONObject row, MailMessage message) {
        return message.id.equals(row.optString("id"))
                || (message.accountId.equals(row.optString("accountId"))
                && message.folder.equals(row.optString("folder"))
                && message.uid.equals(row.optString("uid")));
    }

    private static void trimMessages(JSONArray rows) {
        int max = 2000;
        while (rows.length() > max) rows.remove(0);
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursive(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}

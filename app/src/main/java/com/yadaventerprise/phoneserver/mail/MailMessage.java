package com.yadaventerprise.phoneserver.mail;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MailMessage {

    public String id = "";
    public String accountId = "";
    public String folder = "INBOX";
    public String uid = "";
    public String fromAddress = "";
    public String fromName = "";
    public String to = "";
    public String cc = "";
    public String subject = "";
    public String dateIso = "";
    public long dateMillis;
    public String text = "";
    public String html = "";
    public boolean seen;
    public boolean flagged;
    public final List<MailAttachmentRef> attachments = new ArrayList<>();

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }

    public String snippet() {
        String source = text;
        if (source.trim().isEmpty() && !html.trim().isEmpty()) {
            source = MailHtmlSanitizer.stripTags(html);
        }
        String clean = source.replaceAll("\\s+", " ").trim();
        return clean.length() > 180 ? clean.substring(0, 180) : clean;
    }

    public JSONObject toJson(boolean full) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("accountId", accountId);
        json.put("folder", folder);
        json.put("uid", uid);
        json.put("fromAddress", fromAddress);
        json.put("fromName", fromName);
        json.put("to", to);
        json.put("cc", cc);
        json.put("subject", subject);
        json.put("date", dateIso);
        json.put("dateMillis", dateMillis);
        json.put("seen", seen);
        json.put("flagged", flagged);
        json.put("snippet", snippet());
        json.put("hasAttachments", hasAttachments());
        JSONArray files = new JSONArray();
        for (MailAttachmentRef ref : attachments) files.put(ref.toJson());
        json.put("attachments", files);
        if (full) {
            json.put("text", text);
            json.put("html", MailHtmlSanitizer.sanitize(html));
        }
        return json;
    }

    public static MailMessage fromJson(JSONObject json) {
        MailMessage message = new MailMessage();
        message.id = json.optString("id", "");
        message.accountId = json.optString("accountId", "");
        message.folder = json.optString("folder", "INBOX");
        message.uid = json.optString("uid", "");
        message.fromAddress = json.optString("fromAddress", "");
        message.fromName = json.optString("fromName", "");
        message.to = json.optString("to", "");
        message.cc = json.optString("cc", "");
        message.subject = json.optString("subject", "");
        message.dateIso = json.optString("date", "");
        message.dateMillis = json.optLong("dateMillis", 0L);
        message.text = json.optString("text", "");
        message.html = json.optString("html", "");
        message.seen = json.optBoolean("seen", false);
        message.flagged = json.optBoolean("flagged", false);
        JSONArray files = json.optJSONArray("attachments");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                message.attachments.add(MailAttachmentRef.fromJson(files.optJSONObject(i)));
            }
        }
        return message;
    }
}

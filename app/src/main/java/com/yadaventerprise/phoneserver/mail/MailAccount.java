package com.yadaventerprise.phoneserver.mail;

import org.json.JSONObject;

public final class MailAccount {

    public String id = "";
    public String name = "";
    public String email = "";
    public String username = "";
    public String passwordCipher = "";
    public String smtpHost = "";
    public int smtpPort = 587;
    public String smtpSecurity = "starttls";
    public String imapHost = "";
    public int imapPort = 993;
    public String imapSecurity = "tls";
    public String fromName = "";
    public String sentFolder = "Sent";
    public String draftsFolder = "Drafts";
    public String ownerId = "";
    public long updatedAt;

    public boolean hasPassword() {
        return passwordCipher != null && !passwordCipher.isEmpty();
    }

    public JSONObject toPublicJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("email", email);
        json.put("username", username);
        json.put("smtpHost", smtpHost);
        json.put("smtpPort", smtpPort);
        json.put("smtpSecurity", MailValidation.security(smtpSecurity));
        json.put("imapHost", imapHost);
        json.put("imapPort", imapPort);
        json.put("imapSecurity", MailValidation.security(imapSecurity));
        json.put("fromName", fromName);
        json.put("sentFolder", sentFolder);
        json.put("draftsFolder", draftsFolder);
        json.put("passwordSet", hasPassword());
        json.put("ownerId", ownerId);
        json.put("updatedAt", updatedAt);
        return json;
    }

    public JSONObject toStoredJson() throws Exception {
        JSONObject json = toPublicJson();
        json.remove("passwordSet");
        json.put("passwordCipher", passwordCipher);
        return json;
    }

    public static MailAccount fromJson(JSONObject json) {
        MailAccount account = new MailAccount();
        account.id = json.optString("id", "");
        account.name = json.optString("name", "");
        account.email = json.optString("email", "");
        account.username = json.optString("username", "");
        account.passwordCipher = json.optString("passwordCipher", "");
        account.smtpHost = json.optString("smtpHost", "");
        account.smtpPort = json.optInt("smtpPort", 587);
        account.smtpSecurity = json.optString("smtpSecurity", "starttls");
        account.imapHost = json.optString("imapHost", "");
        account.imapPort = json.optInt("imapPort", 993);
        account.imapSecurity = json.optString("imapSecurity", "tls");
        account.fromName = json.optString("fromName", "");
        account.sentFolder = json.optString("sentFolder", "Sent");
        account.draftsFolder = json.optString("draftsFolder", "Drafts");
        account.ownerId = json.optString("ownerId", "");
        account.updatedAt = json.optLong("updatedAt", 0L);
        return account;
    }
}

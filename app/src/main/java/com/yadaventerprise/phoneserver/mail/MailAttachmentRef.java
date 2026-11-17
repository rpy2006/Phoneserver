package com.yadaventerprise.phoneserver.mail;

import org.json.JSONObject;

public final class MailAttachmentRef {

    public int index;
    public String name = "";
    public String contentType = "";
    public String storageKey = "";
    public long size;

    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("index", index);
        json.put("name", name);
        json.put("contentType", contentType);
        json.put("storageKey", storageKey);
        json.put("size", size);
        return json;
    }

    public static MailAttachmentRef fromJson(JSONObject json) {
        MailAttachmentRef ref = new MailAttachmentRef();
        ref.index = json.optInt("index", 0);
        ref.name = json.optString("name", "attachment");
        ref.contentType = json.optString("contentType", "application/octet-stream");
        ref.storageKey = json.optString("storageKey", "");
        ref.size = json.optLong("size", 0L);
        return ref;
    }
}

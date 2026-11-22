package com.yadaventerprise.phoneserver.network.http;

import android.content.Context;
import android.util.Base64;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import com.yadaventerprise.phoneserver.mail.MailAccount;
import com.yadaventerprise.phoneserver.mail.MailAttachmentRef;
import com.yadaventerprise.phoneserver.mail.MailCompose;
import com.yadaventerprise.phoneserver.mail.MailException;
import com.yadaventerprise.phoneserver.mail.MailMessage;
import com.yadaventerprise.phoneserver.mail.MailService;
import com.yadaventerprise.phoneserver.mail.MailValidation;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MailApi {

    private MailApi() {
    }

    public static void handle(Context context, SimpleHttpServer.Request req, OutputStream out,
                              DataApi.Auth auth, List<String> parts) throws Exception {
        try {
            route(context, req, out, auth, parts);
        } catch (MailException e) {
            throw new DataException(MailValidation.cleanError(e.getMessage()), e.status());
        }
    }

    private static void route(Context context, SimpleHttpServer.Request req, OutputStream out,
                              DataApi.Auth auth, List<String> parts) throws Exception {
        String method = req.method == null ? "GET" : req.method.toUpperCase(Locale.US);
        MailService service = new MailService(context);

        if (parts.size() == 2 && parts.get(1).equals("accounts")) {
            if (method.equals("GET")) {
                DataApi.requirePermission(auth, TokenPolicy.MAIL_READ);
                JSONArray array = new JSONArray();
                for (MailAccount account : service.accounts()) { if (canAccessAccount(auth, account)) array.put(account.toPublicJson()); }
                DataApi.sendJson(context, req, out, 200, new JSONObject().put("accounts", array));
                return;
            }
            if (method.equals("POST")) {
                DataApi.requireWrite(auth, TokenPolicy.MAIL_WRITE);
                JSONObject body = DataApi.readJson(req);
                DataApi.requireBody(body);
                MailAccount account = service.saveAccountForOwner(body, true, auth.ownerId());
                DataApi.sendJson(context, req, out, 201, account.toPublicJson());
                return;
            }
            DataApi.requireMethod(method, "GET", "POST");
        }

        if (parts.size() == 3 && parts.get(1).equals("accounts")) {
            String id = parts.get(2);
            if (method.equals("GET")) {
                DataApi.requirePermission(auth, TokenPolicy.MAIL_READ);
                MailAccount account = service.account(id);
                if (account == null) throw DataException.notFound("mail account");
                requireAccountOwner(auth, account);
                DataApi.sendJson(context, req, out, 200, account.toPublicJson());
                return;
            }
            if (method.equals("PATCH")) {
                DataApi.requireWrite(auth, TokenPolicy.MAIL_WRITE);
                JSONObject body = DataApi.readJson(req);
                DataApi.requireBody(body);
                MailAccount existing = service.account(id); if (existing == null) throw DataException.notFound("mail account"); requireAccountOwner(auth, existing);
                MailAccount account = service.saveAccountForOwner(body.put("id", id), true, auth.ownerId());
                DataApi.sendJson(context, req, out, 200, account.toPublicJson());
                return;
            }
            if (method.equals("DELETE")) {
                DataApi.requireWrite(auth, TokenPolicy.MAIL_WRITE);
                MailAccount existingDel = service.account(id); if (existingDel == null) throw DataException.notFound("mail account"); requireAccountOwner(auth, existingDel);
                service.deleteAccount(id);
                DataApi.sendJson(context, req, out, 200, DataApi.ok());
                return;
            }
            DataApi.requireMethod(method, "GET", "PATCH", "DELETE");
        }

        if (parts.size() == 4 && parts.get(1).equals("accounts")) {
            String id = parts.get(2);
            String action = parts.get(3);
            if (action.equals("test") && method.equals("POST")) {
                DataApi.requireWrite(auth, TokenPolicy.MAIL_WRITE);
                MailAccount a=service.account(id); if(a==null) throw DataException.notFound("mail account"); requireAccountOwner(auth,a);
                service.test(id);
                DataApi.sendJson(context, req, out, 200, DataApi.ok());
                return;
            }
            if (action.equals("sync") && method.equals("POST")) {
                DataApi.requireWrite(auth, TokenPolicy.MAIL_WRITE);
                JSONObject body = DataApi.readJson(req);
                int limit = body.optInt("limit", 50);
                MailAccount a2=service.account(id); if(a2==null) throw DataException.notFound("mail account"); requireAccountOwner(auth,a2);
                int count = service.sync(id, limit);
                JSONObject result = new JSONObject();
                result.put("status", "ok");
                result.put("messages", count);
                DataApi.sendJson(context, req, out, 200, result);
                return;
            }
            if (action.equals("send") && method.equals("POST")) {
                DataApi.requireWrite(auth, TokenPolicy.MAIL_WRITE);
                MailAccount a3=service.account(id); if(a3==null) throw DataException.notFound("mail account"); requireAccountOwner(auth,a3);
                MailCompose compose = compose(DataApi.readJson(req));
                MailMessage sent = service.send(id, compose);
                DataApi.sendJson(context, req, out, 201, sent.toJson(true));
                return;
            }
            if (action.equals("drafts") && method.equals("POST")) {
                DataApi.requireWrite(auth, TokenPolicy.MAIL_WRITE);
                MailAccount a4=service.account(id); if(a4==null) throw DataException.notFound("mail account"); requireAccountOwner(auth,a4);
                MailCompose compose = compose(DataApi.readJson(req));
                MailMessage draft = service.saveDraft(id, compose);
                DataApi.sendJson(context, req, out, 201, draft.toJson(true));
                return;
            }
            if (action.equals("messages") && method.equals("GET")) {
                DataApi.requirePermission(auth, TokenPolicy.MAIL_READ);
                MailAccount a5=service.account(id); if(a5==null) throw DataException.notFound("mail account"); requireAccountOwner(auth,a5);
                String folder = req.query.get("folder");
                String query = req.query.get("q");
                JSONArray array = new JSONArray();
                List<MailMessage> messages = query == null || query.trim().isEmpty()
                        ? service.messages(id, folder)
                        : service.search(id, query);
                for (MailMessage message : messages) array.put(message.toJson(false));
                DataApi.sendJson(context, req, out, 200, new JSONObject().put("messages", array));
                return;
            }
        }

        if (parts.size() >= 3 && parts.get(1).equals("messages")) {
            String id = parts.get(2);
            MailMessage existing = service.message(id);
            if (existing == null) throw DataException.notFound("message");
            MailAccount owner = service.account(existing.accountId);
            if (owner == null) throw DataException.notFound("mail account");
            requireAccountOwner(auth, owner);
            if (parts.size() == 3 && method.equals("GET")) {
                DataApi.requirePermission(auth, TokenPolicy.MAIL_READ);
                DataApi.sendJson(context, req, out, 200, existing.toJson(true));
                return;
            }
            if (parts.size() == 3 && method.equals("DELETE")) {
                DataApi.requireWrite(auth, TokenPolicy.MAIL_WRITE);
                service.deleteLocal(id);
                DataApi.sendJson(context, req, out, 200, DataApi.ok());
                return;
            }
            if (parts.size() == 4 && parts.get(3).equals("read") && method.equals("POST")) {
                DataApi.requireWrite(auth, TokenPolicy.MAIL_WRITE);
                JSONObject body = DataApi.readJson(req);
                boolean seen = !body.has("seen") || body.optBoolean("seen", true);
                service.markRead(id, seen);
                DataApi.sendJson(context, req, out, 200, DataApi.ok());
                return;
            }
            if (parts.size() == 5 && parts.get(3).equals("attachments") && method.equals("GET")) {
                DataApi.requirePermission(auth, TokenPolicy.MAIL_READ);
                int index;
                try {
                    index = Integer.parseInt(parts.get(4));
                } catch (NumberFormatException e) {
                    throw new DataException("invalid attachment index", 400);
                }
                MailService.AttachmentFile file = service.attachment(id, index);
                DataApi.serveDownload(context, req, out, file.file, file.contentType, file.name, true);
                return;
            }
        }

        throw DataException.notFound("endpoint");
    }


    private static boolean canAccessAccount(DataApi.Auth auth, MailAccount account) {
        if (auth == null || auth.admin) return true;
        if (account == null) return false;
        String owner = account.ownerId == null ? "" : account.ownerId;
        return owner.isEmpty() || owner.equals(auth.ownerId());
    }

    private static void requireAccountOwner(DataApi.Auth auth, MailAccount account) throws DataException {
        if (!canAccessAccount(auth, account)) throw DataException.forbidden("mail account is not accessible");
    }
    private static MailCompose compose(JSONObject body) throws Exception {
        DataApi.requireBody(body);
        MailCompose compose = new MailCompose();
        compose.to = body.optString("to", "");
        compose.cc = body.optString("cc", "");
        compose.bcc = body.optString("bcc", "");
        compose.subject = body.optString("subject", "");
        compose.text = body.optString("text", "");
        compose.html = body.optString("html", "");
        compose.inReplyTo = body.optString("inReplyTo", "");
        compose.references = body.optString("references", "");
        JSONArray files = body.optJSONArray("attachments");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject row = files.optJSONObject(i);
                if (row == null) continue;
                String data = row.optString("data", "");
                byte[] bytes;
                try {
                    bytes = Base64.decode(data, Base64.DEFAULT);
                } catch (IllegalArgumentException e) {
                    throw new DataException("attachment data is not valid base64", 400);
                }
                compose.attachments.add(new MailCompose.OutgoingAttachment(
                        row.optString("name", "attachment"),
                        row.optString("contentType", "application/octet-stream"),
                        bytes));
            }
        }
        compose.validate();
        return compose;
    }
}

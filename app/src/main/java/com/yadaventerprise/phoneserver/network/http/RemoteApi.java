package com.yadaventerprise.phoneserver.network.http;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.yadaventerprise.phoneserver.data.remote.RemoteAuditEntry;
import com.yadaventerprise.phoneserver.data.remote.RemoteAuditStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteDevice;
import com.yadaventerprise.phoneserver.data.remote.RemoteDeviceStore;
import com.yadaventerprise.phoneserver.data.remote.RemotePairingRequest;
import com.yadaventerprise.phoneserver.data.remote.RemotePairingStore;
import com.yadaventerprise.phoneserver.data.remote.RemotePermissions;
import com.yadaventerprise.phoneserver.data.remote.RemoteRole;
import com.yadaventerprise.phoneserver.data.remote.RemoteSession;
import com.yadaventerprise.phoneserver.data.remote.RemoteSessionStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettings;
import com.yadaventerprise.phoneserver.data.remote.RemoteSettingsStore;
import com.yadaventerprise.phoneserver.data.remote.RemoteUser;
import com.yadaventerprise.phoneserver.data.remote.RemoteUserStore;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.service.ServerService;
import com.yadaventerprise.phoneserver.util.MimeTypes;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RemoteApi {

    private static final Map<String, LoginAttempts> LOGIN_ATTEMPTS = new ConcurrentHashMap<>();

    private RemoteApi() {
    }

    static boolean isPublicPath(List<String> parts) {
        if (parts.size() < 3 || !parts.get(0).equals("remote")) return false;
        if (parts.get(1).equals("auth")) return parts.get(2).equals("login");
        return parts.get(1).equals("pairing")
                && (parts.get(2).equals("request") || parts.get(2).equals("status"));
    }

    static void handlePublic(Context context, SimpleHttpServer.Request req, OutputStream out,
                             List<String> parts) throws Exception {
        String method = req.method == null ? "GET" : req.method.toUpperCase(Locale.US);
        RemoteSettings settings = RemoteSettingsStore.get(context).get();
        enforceTransport(req, settings, true);
        String section = parts.get(1);
        if (section.equals("auth")) {
            DataApi.requireMethod(method, "POST");
            login(context, req, out, settings);
            return;
        }
        String action = parts.get(2);
        if (action.equals("request")) {
            DataApi.requireMethod(method, "POST");
            if (!settings.allowPairing) throw DataException.forbidden("device pairing is disabled");
            requestPairing(context, req, out, settings);
            return;
        }
        DataApi.requireMethod(method, "POST");
        if (!settings.allowPairing) throw DataException.forbidden("device pairing is disabled");
        pairingStatus(context, req, out, settings);
    }

    static void handle(Context context, SimpleHttpServer.Request req, OutputStream out,
                       DataApi.Auth auth, List<String> parts) throws Exception {
        String method = req.method == null ? "GET" : req.method.toUpperCase(Locale.US);
        RemoteSettings settings = RemoteSettingsStore.get(context).get();
        enforceTransport(req, settings, false);
        Caller caller = caller(context, req, auth, settings);
        if (caller == null) throw DataException.forbidden("remote access credential required");

        String section = parts.size() > 1 ? parts.get(1) : "";
        switch (section) {
            case "dashboard":
                DataApi.requireMethod(method, "GET");
                require(context, req, caller, RemotePermissions.DASHBOARD);
                DataApi.sendJson(context, req, out, 200, dashboard(context, settings));
                return;
            case "auth":
                if (parts.size() > 2 && parts.get(2).equals("logout")) {
                    DataApi.requireMethod(method, "POST");
                    logout(context, req, out, caller);
                    return;
                }
                throw DataException.notFound("endpoint");
            case "pairing":
                handlePairing(context, req, out, caller, settings, method, parts);
                return;
            case "devices":
                handleDevices(context, req, out, caller, settings, method, parts);
                return;
            case "users":
                handleUsers(context, req, out, caller, method, parts);
                return;
            case "roles":
                DataApi.requireMethod(method, "GET");
                require(context, req, caller, RemotePermissions.ROLES_READ);
                DataApi.sendJson(context, req, out, 200, rolesJson());
                return;
            case "audit":
                DataApi.requireMethod(method, "GET");
                require(context, req, caller, RemotePermissions.AUDIT_READ);
                int limit = boundedInt(req.query.get("limit"), 20, 500, 200);
                JSONArray entries = new JSONArray();
                for (RemoteAuditEntry entry : RemoteAuditStore.get(context).all(limit)) {
                    entries.put(entry.toJson());
                }
                DataApi.sendJson(context, req, out, 200, DataApi.wrap("entries", entries));
                return;
            case "settings":
                handleSettings(context, req, out, caller, method);
                return;
            case "server":
                handleServer(context, req, out, caller, settings, method, parts);
                return;
            case "files":
                handleFiles(context, req, out, caller, settings, method, parts);
                return;
            default:
                throw DataException.notFound("endpoint");
        }
    }

    private static void login(Context context, SimpleHttpServer.Request req, OutputStream out,
                              RemoteSettings settings) throws Exception {
        JSONObject body = DataApi.readJson(req);
        String username = RemoteUser.normalizeUsername(body.optString("username", ""));
        String password = body.optString("password", "");
        LoginAttempts attempts = LOGIN_ATTEMPTS.get(req.remoteIp);
        if (attempts != null && attempts.locked(System.currentTimeMillis())) {
            throw DataException.forbidden("too many login attempts; try again later");
        }
        RemoteUser user = RemoteUserStore.get(context).findByUsername(username);
        boolean ok = user != null && user.isActive() && user.verifyPassword(password);
        if (!ok) {
            if (attempts == null) {
                attempts = new LoginAttempts();
                LOGIN_ATTEMPTS.put(req.remoteIp, attempts);
            }
            attempts.failed = attempts.failed + 1;
            attempts.firstFailureAt = attempts.firstFailureAt == 0 ? System.currentTimeMillis() : attempts.firstFailureAt;
            if (user != null) {
                user.failedAttempts++;
                if (user.failedAttempts >= settings.maxFailedLoginAttempts) {
                    user.lockedUntil = System.currentTimeMillis() + settings.lockoutMinutes * 60_000L;
                    user.failedAttempts = 0;
                }
                RemoteUserStore.get(context).save(user);
            }
            RemoteAuditStore.get(context).append(RemoteAuditEntry.AUTH_FAILED, username, username,
                    req.remoteIp, "remote login rejected", false);
            if (attempts.locked(System.currentTimeMillis())) {
                throw DataException.forbidden("too many login attempts; try again later");
            }
            throw new DataException("invalid username or password", 401);
        }
        LOGIN_ATTEMPTS.remove(req.remoteIp);
        user.failedAttempts = 0;
        user.lockedUntil = 0;
        user.lastLoginAt = System.currentTimeMillis();
        user = RemoteUserStore.get(context).save(user);
        long ttl = settings.sessionTtlMinutes * 60_000L;
        RemoteSessionStore.Created created = RemoteSessionStore.get(context).create(
                RemoteSession.SUBJECT_USER, user.id, ttl, req.remoteIp,
                user.grantedPermissions, user.deniedPermissions);
        if (created == null) throw new DataException("could not create session", 500);
        RemoteAuditStore.get(context).append(RemoteAuditEntry.AUTH_LOGIN, user.username, user.id,
                req.remoteIp, "remote session created", true);
        JSONObject json = new JSONObject();
        json.put("token", created.token);
        json.put("expiresAt", created.session.expiresAt);
        json.put("user", userJson(user));
        json.put("warning", "store this token securely; it is shown only once");
        DataApi.sendJson(context, req, out, 200, json);
    }

    private static void logout(Context context, SimpleHttpServer.Request req, OutputStream out,
                               Caller caller) throws Exception {
        if (caller.session == null) throw DataException.forbidden("session credential required");
        RemoteSessionStore.get(context).revoke(caller.session.id);
        RemoteAuditStore.get(context).append(RemoteAuditEntry.AUTH_LOGOUT, caller.actor, caller.session.subjectId,
                req.remoteIp, "remote session revoked", true);
        DataApi.sendJson(context, req, out, 200, DataApi.ok());
    }

    private static void requestPairing(Context context, SimpleHttpServer.Request req, OutputStream out,
                                       RemoteSettings settings) throws Exception {
        JSONObject body = DataApi.readJson(req);
        RemotePairingStore store = RemotePairingStore.get(context);
        RemotePairingRequest found = store.findByCode(body.optString("code", ""));
        if (found == null) {
            RemoteAuditStore.get(context).append(RemoteAuditEntry.PAIRING_CREATED, "device", "",
                    req.remoteIp, "invalid pairing code", false);
            throw new DataException("invalid pairing code", 401);
        }
        JSONObject info = body.optJSONObject("deviceInfo");
        RemotePairingRequest updated = store.submitDeviceInfo(found.id,
                body.optString("name", ""), info);
        if (updated == null) throw new DataException("pairing request expired", 401);
        JSONObject json = new JSONObject();
        json.put("requestId", updated.id);
        json.put("status", updated.status);
        json.put("expiresAt", updated.expiresAt);
        json.put("pollAfterSeconds", 3);
        DataApi.sendJson(context, req, out, 200, json);
    }

    private static void pairingStatus(Context context, SimpleHttpServer.Request req, OutputStream out,
                                      RemoteSettings settings) throws Exception {
        JSONObject body = DataApi.readJson(req);
        RemotePairingStore pairingStore = RemotePairingStore.get(context);
        RemotePairingRequest found = pairingStore.findByCode(body.optString("code", ""));
        if (found == null) throw new DataException("invalid pairing code", 401);
        if (!RemotePairingRequest.STATUS_APPROVED.equals(found.status)) {
            JSONObject pending = new JSONObject();
            pending.put("requestId", found.id);
            pending.put("status", found.status);
            if (RemotePairingRequest.STATUS_REJECTED.equals(found.status)) {
                pending.put("reason", "pairing request was rejected");
            }
            DataApi.sendJson(context, req, out, 200, pending);
            return;
        }
        String deviceId = found.deviceId;
        if (deviceId == null || deviceId.isEmpty()) throw new DataException("pairing approval is incomplete", 409);
        long ttl = settings.deviceSessionTtlDays * 24L * 60L * 60L * 1000L;
        RemoteSessionStore.Created created = RemoteSessionStore.get(context).create(
                RemoteSession.SUBJECT_DEVICE, deviceId, ttl, req.remoteIp, null, null);
        if (created == null) throw new DataException("could not create device session", 500);
        if (!pairingStore.consume(found.id, created.session.tokenHash)) {
            RemoteSessionStore.get(context).revoke(created.session.id);
            throw new DataException("pairing request is no longer available", 409);
        }
        RemoteDevice device = RemoteDeviceStore.get(context).touch(deviceId);
        if (device != null) {
            device.sessionId = created.session.id;
            RemoteDeviceStore.get(context).save(device);
        }
        RemoteAuditStore.get(context).append(RemoteAuditEntry.PAIRING_CONSUMED, "device", deviceId,
                req.remoteIp, "device session issued", true);
        JSONObject json = new JSONObject();
        json.put("requestId", found.id);
        json.put("status", RemotePairingRequest.STATUS_CONSUMED);
        json.put("token", created.token);
        json.put("expiresAt", created.session.expiresAt);
        json.put("deviceId", deviceId);
        json.put("warning", "store this token on the device; it is shown only once");
        DataApi.sendJson(context, req, out, 200, json);
    }

    private static void handlePairing(Context context, SimpleHttpServer.Request req, OutputStream out,
                                      Caller caller, RemoteSettings settings, String method,
                                      List<String> parts) throws Exception {
        RemotePairingStore store = RemotePairingStore.get(context);
        if (parts.size() == 2) {
            if (method.equals("GET")) {
                require(context, req, caller, RemotePermissions.PAIRING);
                JSONArray array = new JSONArray();
                for (RemotePairingRequest request : store.all()) array.put(request.publicJson());
                DataApi.sendJson(context, req, out, 200, DataApi.wrap("requests", array));
                return;
            }
            if (method.equals("POST")) {
                require(context, req, caller, RemotePermissions.PAIRING);
                JSONObject body = DataApi.readJson(req);
                RemotePairingStore.Created created = store.create(
                        body.optString("name", ""), settings.pairingTtlMinutes * 60_000L);
                RemoteAuditStore.get(context).append(RemoteAuditEntry.PAIRING_CREATED, caller.actor,
                        created.request.id, req.remoteIp, "pairing code generated", true);
                JSONObject json = created.request.publicJson();
                json.put("code", created.code);
                json.put("qrPayload", qrPayload(req, created.code));
                DataApi.sendJson(context, req, out, 201, json);
                return;
            }
            throw new DataException("method not allowed", 405);
        }
        String id = decode(parts.get(2));
        if (parts.size() == 3 && method.equals("DELETE")) {
            require(context, req, caller, RemotePermissions.PAIRING);
            if (store.reject(id, caller.actor) == null) throw DataException.notFound("pairing request");
            audit(context, req, RemoteAuditEntry.PAIRING_REJECTED, caller, id, "pairing request rejected", true);
            DataApi.sendJson(context, req, out, 200, DataApi.ok());
            return;
        }
        if (parts.size() == 4 && method.equals("POST")) {
            require(context, req, caller, RemotePermissions.PAIRING);
            String action = parts.get(3);
            if (action.equals("reject")) {
                if (store.reject(id, caller.actor) == null) throw DataException.notFound("pairing request");
                audit(context, req, RemoteAuditEntry.PAIRING_REJECTED, caller, id, "pairing request rejected", true);
                DataApi.sendJson(context, req, out, 200, DataApi.ok());
                return;
            }
            if (action.equals("approve")) {
                RemotePairingRequest pending = store.get(id);
                if (pending == null) throw DataException.notFound("pairing request");
                String deviceId = UUID.randomUUID().toString();
                RemoteDevice draft = new RemoteDevice();
                draft.name = pending.deviceName.isEmpty() ? pending.requestedName : pending.deviceName;
                JSONObject info = null;
                try {
                    info = new JSONObject(pending.deviceInfo);
                } catch (Exception ignored) {
                }
                if (info != null) draft.applyHeartbeat(info);
                RemoteDevice registered = RemoteDeviceStore.get(context).register(deviceId, draft);
                RemotePairingRequest approved = store.approve(id, caller.actor, deviceId);
                if (approved == null) {
                    if (registered != null) {
                        RemoteDeviceStore.get(context).revoke(registered.id);
                    }
                    throw DataException.notFound("pairing request");
                }
                audit(context, req, RemoteAuditEntry.PAIRING_APPROVED, caller, id,
                        "device " + deviceId + " approved", true);
                DataApi.sendJson(context, req, out, 200, approved.publicJson());
                return;
            }
        }
        throw DataException.notFound("endpoint");
    }

    private static void handleDevices(Context context, SimpleHttpServer.Request req, OutputStream out,
                                      Caller caller, RemoteSettings settings, String method,
                                      List<String> parts) throws Exception {
        RemoteDeviceStore store = RemoteDeviceStore.get(context);
        if (parts.size() == 2) {
            DataApi.requireMethod(method, "GET");
            require(context, req, caller, RemotePermissions.DEVICES_READ);
            JSONArray array = new JSONArray();
            for (RemoteDevice device : store.all()) array.put(deviceJson(device, settings));
            DataApi.sendJson(context, req, out, 200, DataApi.wrap("devices", array));
            return;
        }
        String id = decode(parts.get(2));
        if (parts.size() == 3) {
            if (method.equals("GET")) {
                require(context, req, caller, RemotePermissions.DEVICES_READ);
                RemoteDevice device = store.get(id);
                if (device == null) throw DataException.notFound("device");
                DataApi.sendJson(context, req, out, 200, deviceJson(device, settings));
                return;
            }
            if (method.equals("PATCH") || method.equals("PUT")) {
                require(context, req, caller, RemotePermissions.DEVICES_WRITE);
                JSONObject body = DataApi.readJson(req);
                RemoteDevice device = store.rename(id, body.optString("name", ""));
                if (device == null) throw DataException.notFound("device");
                audit(context, req, RemoteAuditEntry.DEVICE_UPDATED, caller, id,
                        "device renamed to " + device.name, true);
                DataApi.sendJson(context, req, out, 200, deviceJson(device, settings));
                return;
            }
            if (method.equals("DELETE")) {
                require(context, req, caller, RemotePermissions.DEVICES_WRITE);
                revokeDevice(context, id, caller, req);
                DataApi.sendJson(context, req, out, 200, DataApi.ok());
                return;
            }
            throw new DataException("method not allowed", 405);
        }
        if (parts.size() == 4 && method.equals("POST")) {
            String action = parts.get(3);
            if (action.equals("heartbeat")) {
                require(context, req, caller, RemotePermissions.DEVICES_READ);
                if (caller.session != null && RemoteSession.SUBJECT_DEVICE.equals(caller.session.subjectType)
                        && !id.equals(caller.session.subjectId)) {
                    audit(context, req, RemoteAuditEntry.ACCESS_DENIED, caller, id,
                            "device heartbeat for another device", false);
                    throw DataException.forbidden("device can only report its own status");
                }
                JSONObject report = DataApi.readJson(req);
                RemoteDevice device = store.heartbeat(id, report);
                if (device == null) throw DataException.notFound("device");
                audit(context, req, RemoteAuditEntry.DEVICE_HEARTBEAT, caller, id,
                        "heartbeat accepted", true);
                DataApi.sendJson(context, req, out, 200, deviceJson(device, settings));
                return;
            }
            if (action.equals("disconnect")) {
                require(context, req, caller, RemotePermissions.DEVICES_WRITE);
                RemoteDevice device = store.disconnect(id);
                if (device == null) throw DataException.notFound("device");
                if (!device.sessionId.isEmpty()) RemoteSessionStore.get(context).revoke(device.sessionId);
                audit(context, req, RemoteAuditEntry.DEVICE_UPDATED, caller, id,
                        "device disconnected", true);
                DataApi.sendJson(context, req, out, 200, deviceJson(store.get(id), settings));
                return;
            }
        }
        throw DataException.notFound("endpoint");
    }

    private static void revokeDevice(Context context, String id, Caller caller,
                                     SimpleHttpServer.Request req) {
        RemoteDevice device = RemoteDeviceStore.get(context).revoke(id);
        if (device == null) return;
        if (!device.sessionId.isEmpty()) RemoteSessionStore.get(context).revoke(device.sessionId);
        RemoteSessionStore.get(context).revokeSubject(RemoteSession.SUBJECT_DEVICE, id);
        audit(context, req, RemoteAuditEntry.DEVICE_REVOKED, caller, id, "device revoked", true);
    }

    private static void handleUsers(Context context, SimpleHttpServer.Request req, OutputStream out,
                                    Caller caller, String method, List<String> parts) throws Exception {
        RemoteUserStore store = RemoteUserStore.get(context);
        if (parts.size() == 2) {
            if (method.equals("GET")) {
                require(context, req, caller, RemotePermissions.USERS_READ);
                JSONArray array = new JSONArray();
                for (RemoteUser user : store.all()) array.put(userJson(user));
                DataApi.sendJson(context, req, out, 200, DataApi.wrap("users", array));
                return;
            }
            if (method.equals("POST")) {
                require(context, req, caller, RemotePermissions.USERS_WRITE);
                JSONObject body = DataApi.readJson(req);
                RemoteUser draft = new RemoteUser();
                draft.username = RemoteUser.normalizeUsername(body.optString("username", ""));
                draft.displayName = body.optString("displayName", "");
                draft.role = RemoteRole.normalize(body.optString("role", RemoteRole.VIEWER));
                readPermissionSet(body.optJSONArray("grantedPermissions"), draft.grantedPermissions);
                readPermissionSet(body.optJSONArray("deniedPermissions"), draft.deniedPermissions);
                String password = body.optString("password", "");
                if (password.length() < 10) throw new DataException("password must be at least 10 characters", 400);
                RemoteUser created = store.create(draft, password);
                if (created == null) throw DataException.conflict("username is invalid or already exists");
                audit(context, req, RemoteAuditEntry.USER_CREATED, caller, created.id,
                        "created user " + created.username, true);
                DataApi.sendJson(context, req, out, 201, userJson(created));
                return;
            }
            throw new DataException("method not allowed", 405);
        }
        String id = decode(parts.get(2));
        RemoteUser current = store.get(id);
        if (current == null) throw DataException.notFound("user");
        if (parts.size() == 3) {
            if (method.equals("GET")) {
                require(context, req, caller, RemotePermissions.USERS_READ);
                DataApi.sendJson(context, req, out, 200, userJson(current));
                return;
            }
            if (method.equals("PATCH") || method.equals("PUT")) {
                require(context, req, caller, RemotePermissions.USERS_WRITE);
                protectOwner(current, caller);
                JSONObject body = DataApi.readJson(req);
                if (body.has("displayName")) current.displayName = body.optString("displayName", "");
                if (body.has("role")) {
                    String role = RemoteRole.normalize(body.optString("role", current.role));
                    if (current.isOwner() && !RemoteRole.OWNER.equals(role)) {
                        throw DataException.forbidden("the owner role cannot be changed");
                    }
                    current.role = role;
                }
                if (body.has("status")) {
                    String status = body.optString("status", current.status);
                    if (!RemoteUser.STATUS_ACTIVE.equals(status) && !RemoteUser.STATUS_SUSPENDED.equals(status)
                            && !RemoteUser.STATUS_REVOKED.equals(status)) {
                        throw new DataException("unknown user status", 400);
                    }
                    if (current.isOwner() && !RemoteUser.STATUS_ACTIVE.equals(status)) {
                        throw DataException.forbidden("the owner cannot be suspended or revoked");
                    }
                    current.status = status;
                }
                if (body.has("password")) {
                    String password = body.optString("password", "");
                    if (password.length() < 10) throw new DataException("password must be at least 10 characters", 400);
                    current.passwordHash = com.yadaventerprise.phoneserver.data.sharing.SharePassword.hash(password);
                }
                if (body.has("grantedPermissions")) {
                    readPermissionSet(body.optJSONArray("grantedPermissions"), current.grantedPermissions);
                }
                if (body.has("deniedPermissions")) {
                    readPermissionSet(body.optJSONArray("deniedPermissions"), current.deniedPermissions);
                }
                RemoteUser saved = store.save(current);
                RemoteSessionStore.get(context).revokeSubject(RemoteSession.SUBJECT_USER, saved.id);
                audit(context, req, RemoteAuditEntry.USER_UPDATED, caller, id,
                        "user " + saved.username + " updated", true);
                DataApi.sendJson(context, req, out, 200, userJson(saved));
                return;
            }
            if (method.equals("DELETE")) {
                require(context, req, caller, RemotePermissions.USERS_WRITE);
                protectOwner(current, caller);
                store.delete(id);
                RemoteSessionStore.get(context).revokeSubject(RemoteSession.SUBJECT_USER, id);
                audit(context, req, RemoteAuditEntry.USER_DELETED, caller, id,
                        "user " + current.username + " deleted", true);
                DataApi.sendJson(context, req, out, 200, DataApi.ok());
                return;
            }
            throw new DataException("method not allowed", 405);
        }
        if (parts.size() == 4 && parts.get(3).equals("sessions") && method.equals("DELETE")) {
            require(context, req, caller, RemotePermissions.USERS_WRITE);
            RemoteSessionStore.get(context).revokeSubject(RemoteSession.SUBJECT_USER, id);
            audit(context, req, RemoteAuditEntry.AUTH_LOGOUT, caller, id, "all user sessions revoked", true);
            DataApi.sendJson(context, req, out, 200, DataApi.ok());
            return;
        }
        throw DataException.notFound("endpoint");
    }

    private static void protectOwner(RemoteUser target, Caller caller) throws DataException {
        if (!target.isOwner()) return;
        if (caller.user != null && caller.user.isOwner()) return;
        throw DataException.forbidden("only the owner can change the owner account");
    }

    private static void handleSettings(Context context, SimpleHttpServer.Request req, OutputStream out,
                                       Caller caller, String method) throws Exception {
        RemoteSettingsStore store = RemoteSettingsStore.get(context);
        if (method.equals("GET")) {
            require(context, req, caller, RemotePermissions.SETTINGS_READ);
            DataApi.sendJson(context, req, out, 200, store.get().toJson());
            return;
        }
        if (method.equals("PATCH") || method.equals("PUT")) {
            require(context, req, caller, RemotePermissions.SETTINGS_WRITE);
            RemoteSettings settings = store.get();
            settings.apply(DataApi.readJson(req));
            store.save(settings);
            audit(context, req, RemoteAuditEntry.SETTINGS_CHANGED, caller, "remote",
                    "remote access settings updated", true);
            DataApi.sendJson(context, req, out, 200, store.get().toJson());
            return;
        }
        throw new DataException("method not allowed", 405);
    }

    private static void handleServer(Context context, SimpleHttpServer.Request req, OutputStream out,
                                     Caller caller, RemoteSettings settings, String method,
                                     List<String> parts) throws Exception {
        if (parts.size() != 3) throw DataException.notFound("endpoint");
        DataApi.requireMethod(method, "POST");
        require(context, req, caller, RemotePermissions.SERVER_CONTROL);
        if (!settings.allowServerControl) throw DataException.forbidden("remote server control is disabled");
        String action = parts.get(2);
        if (!action.equals("start") && !action.equals("stop") && !action.equals("restart")) {
            throw DataException.notFound("endpoint");
        }
        Intent intent = new Intent(context, ServerService.class);
        if (action.equals("start")) intent.setAction(ServerService.ACTION_START);
        else if (action.equals("stop")) intent.setAction(ServerService.ACTION_STOP);
        else intent.setAction(ServerService.ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
        audit(context, req, RemoteAuditEntry.SERVER_CONTROL, caller, action,
                "remote server " + action + " requested", true);
        DataApi.sendJson(context, req, out, 200, DataApi.ok());
    }

    private static void handleFiles(Context context, SimpleHttpServer.Request req, OutputStream out,
                                    Caller caller, RemoteSettings settings, String method,
                                    List<String> parts) throws Exception {
        if (!settings.allowRemoteFiles) throw DataException.forbidden("remote file access is disabled");
        String action = parts.size() > 2 ? parts.get(2) : "list";
        if (action.equals("list") && method.equals("GET")) {
            require(context, req, caller, RemotePermissions.FILES_READ);
            File root = ContentStore.getPublicFilesDir(context);
            File dir = resolve(context, req.query.get("path"));
            if (!dir.isDirectory()) throw new DataException("directory not found", 404);
            JSONArray array = new JSONArray();
            File[] children = dir.listFiles();
            if (children != null) {
                Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File child : children) {
                    if (!ContentStore.getShowHiddenFiles(context) && child.getName().startsWith(".")) continue;
                    array.put(fileJson(root, child));
                }
            }
            audit(context, req, RemoteAuditEntry.FILE_LISTED, caller, relative(root, dir),
                    "remote directory listed", true);
            DataApi.sendJson(context, req, out, 200, DataApi.wrap("files", array));
            return;
        }
        if (action.equals("download") && method.equals("GET")) {
            require(context, req, caller, RemotePermissions.FILES_READ);
            File file = resolve(context, req.query.get("path"));
            if (!file.isFile()) throw DataException.notFound("file");
            String mime = MimeTypes.guess(file.getName());
            boolean inline = "1".equals(req.query.get("inline")) && MimeTypes.isInlineSafe(mime);
            audit(context, req, RemoteAuditEntry.FILE_DOWNLOADED, caller,
                    relative(ContentStore.getPublicFilesDir(context), file), "remote file downloaded", true);
            DataApi.serveDownload(context, req, out, file, mime, file.getName(), !inline);
            return;
        }
        if (action.equals("upload") && method.equals("POST")) {
            require(context, req, caller, RemotePermissions.FILES_WRITE);
            File target = resolveWritable(context, req.query.get("path"), req.query.get("name"),
                    "true".equals(req.query.get("overwrite")));
            long written = copyBody(req, target);
            ContentStore.scanFile(context, target);
            audit(context, req, RemoteAuditEntry.FILE_UPLOADED, caller,
                    relative(ContentStore.getPublicFilesDir(context), target),
                    "uploaded " + written + " bytes", true);
            JSONObject json = fileJson(ContentStore.getPublicFilesDir(context), target);
            json.put("bytesWritten", written);
            DataApi.sendJson(context, req, out, 201, json);
            return;
        }
        if (action.equals("delete") && method.equals("POST")) {
            require(context, req, caller, RemotePermissions.FILES_WRITE);
            JSONObject body = DataApi.readJson(req);
            File target = resolve(context, body.optString("path", ""));
            File root = ContentStore.getPublicFilesDir(context);
            if (samePath(root, target)) throw DataException.forbidden("the storage root cannot be deleted");
            if (!target.exists()) throw DataException.notFound("file");
            deleteRecursively(target);
            audit(context, req, RemoteAuditEntry.FILE_DELETED, caller, relative(root, target),
                    "remote file deleted", true);
            DataApi.sendJson(context, req, out, 200, DataApi.ok());
            return;
        }
        if (action.equals("rename") && method.equals("POST")) {
            require(context, req, caller, RemotePermissions.FILES_WRITE);
            JSONObject body = DataApi.readJson(req);
            File target = resolve(context, body.optString("path", ""));
            String newName = sanitizeFileName(body.optString("newName", ""));
            if (newName.isEmpty()) throw new DataException("invalid file name", 400);
            File renamed = new File(target.getParentFile(), newName);
            if (!target.exists() || renamed.exists()) throw DataException.conflict("cannot rename file");
            if (!target.renameTo(renamed)) throw new DataException("could not rename file", 500);
            ContentStore.scanFile(context, renamed);
            audit(context, req, RemoteAuditEntry.FILE_UPDATED, caller,
                    relative(ContentStore.getPublicFilesDir(context), renamed), "remote file renamed", true);
            DataApi.sendJson(context, req, out, 200,
                    fileJson(ContentStore.getPublicFilesDir(context), renamed));
            return;
        }
        if (action.equals("mkdir") && method.equals("POST")) {
            require(context, req, caller, RemotePermissions.FILES_WRITE);
            JSONObject body = DataApi.readJson(req);
            File parent = resolve(context, body.optString("path", ""));
            String name = sanitizeFileName(body.optString("name", ""));
            if (name.isEmpty() || !parent.isDirectory()) throw new DataException("invalid folder", 400);
            File created = new File(parent, name);
            if (created.exists() || !created.mkdirs()) throw DataException.conflict("cannot create folder");
            audit(context, req, RemoteAuditEntry.FILE_UPDATED, caller,
                    relative(ContentStore.getPublicFilesDir(context), created), "remote folder created", true);
            DataApi.sendJson(context, req, out, 201,
                    fileJson(ContentStore.getPublicFilesDir(context), created));
            return;
        }
        throw DataException.notFound("endpoint");
    }

    private static long copyBody(SimpleHttpServer.Request req, File target) throws Exception {
        InputStream in = req.bodyStream != null
                ? new BufferedInputStream(req.bodyStream)
                : new java.io.ByteArrayInputStream(req.body == null ? new byte[0] : req.body);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        long max = ContentStore.DEFAULT_MAX_UPLOAD_BYTES;
        long written = 0;
        FileOutputStream out = new FileOutputStream(target, false);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > max) throw DataException.tooLarge("upload exceeds the size limit");
                out.write(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
            if (req.bodyStream != null) in.close();
        }
        return written;
    }

    private static File resolve(Context context, String path) throws DataException {
        File file = ContentStore.resolveSafePath(context, path);
        if (file == null) throw new DataException("unsafe path", 400);
        return file;
    }

    private static File resolveWritable(Context context, String path, String name, boolean overwrite)
            throws DataException {
        File dir = resolve(context, path);
        String cleaned = sanitizeFileName(name);
        if (cleaned.isEmpty()) throw new DataException("invalid file name", 400);
        if (!dir.isDirectory()) throw new DataException("directory not found", 404);
        File target = new File(dir, cleaned);
        if (!overwrite && target.exists()) throw DataException.conflict("file already exists");
        return target;
    }

    private static String sanitizeFileName(String name) {
        if (name == null) return "";
        String cleaned = name.trim().replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) cleaned = cleaned.substring(slash + 1);
        if (cleaned.equals(".") || cleaned.equals("..")) return "";
        return cleaned.length() > 180 ? cleaned.substring(0, 180) : cleaned;
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static boolean samePath(File a, File b) throws Exception {
        return a.getCanonicalPath().equals(b.getCanonicalPath());
    }

    private static String relative(File root, File file) throws Exception {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (filePath.equals(rootPath)) return "";
        String prefix = rootPath + File.separator;
        if (filePath.startsWith(prefix)) return filePath.substring(prefix.length()).replace(File.separatorChar, '/');
        return file.getName();
    }

    private static JSONObject fileJson(File root, File file) throws Exception {
        JSONObject json = new JSONObject();
        json.put("name", file.getName());
        json.put("path", relative(root, file));
        json.put("directory", file.isDirectory());
        json.put("sizeBytes", file.isFile() ? file.length() : 0);
        json.put("modifiedAt", file.lastModified());
        return json;
    }

    private static JSONObject dashboard(Context context, RemoteSettings settings) throws Exception {
        com.yadaventerprise.phoneserver.data.storage.ProjectStore.GlobalStats stats =
                com.yadaventerprise.phoneserver.data.storage.ProjectStore.globalStats(context);
        List<RemoteDevice> devices = RemoteDeviceStore.get(context).all();
        long now = System.currentTimeMillis();
        int online = 0;
        for (RemoteDevice device : devices) {
            if (device.isOnline(now, settings.offlineThresholdSeconds * 1000L)) online++;
        }
        JSONObject json = new JSONObject();
        json.put("serverRunning", ServerService.isServerRunning());
        json.put("httpsRunning", ServerService.isHttpsRunning());
        json.put("uptimeMillis", Math.max(0, ServerService.getUptimeMillis()));
        json.put("devicesTotal", devices.size());
        json.put("devicesOnline", online);
        json.put("pendingPairing", pendingPairing(context));
        json.put("usersTotal", RemoteUserStore.get(context).all().size());
        json.put("sessionsActive", activeSessions(context));
        json.put("projects", stats.projectCount);
        json.put("files", stats.fileCount);
        json.put("filesBytes", stats.fileBytes);
        json.put("lastAuditAt", lastAuditAt(context));
        json.put("requireHttps", settings.requireHttps);
        json.put("remoteFilesEnabled", settings.allowRemoteFiles);
        json.put("serverControlEnabled", settings.allowServerControl);
        return json;
    }

    private static int pendingPairing(Context context) {
        int count = 0;
        for (RemotePairingRequest request : RemotePairingStore.get(context).all()) {
            if (RemotePairingRequest.STATUS_PENDING.equals(request.status)) count++;
        }
        return count;
    }

    private static int activeSessions(Context context) {
        int count = 0;
        for (RemoteSession session : RemoteSessionStore.get(context).all()) {
            if (session.isActive()) count++;
        }
        return count;
    }

    private static long lastAuditAt(Context context) {
        List<RemoteAuditEntry> entries = RemoteAuditStore.get(context).all(1);
        return entries.isEmpty() ? 0 : entries.get(0).timestamp;
    }

    private static JSONObject rolesJson() throws Exception {
        JSONArray roles = new JSONArray();
        roles.put(RemoteRole.OWNER);
        roles.put(RemoteRole.ADMINISTRATOR);
        roles.put(RemoteRole.MANAGER);
        roles.put(RemoteRole.VIEWER);
        roles.put(RemoteRole.DEVICE);
        JSONObject json = new JSONObject();
        json.put("roles", roles);
        JSONObject permissions = new JSONObject();
        List<String> allRoles = Arrays.asList(RemoteRole.OWNER, RemoteRole.ADMINISTRATOR,
                RemoteRole.MANAGER, RemoteRole.VIEWER, RemoteRole.DEVICE);
        for (String role : allRoles) {
            permissions.put(role, new JSONArray(RemotePermissions.forRole(role)));
        }
        json.put("permissions", permissions);
        json.put("allPermissions", new JSONArray(RemotePermissions.ALL));
        return json;
    }

    private static JSONObject deviceJson(RemoteDevice device, RemoteSettings settings) throws Exception {
        JSONObject json = device.toJson();
        json.remove("sessionId");
        long now = System.currentTimeMillis();
        boolean online = device.isOnline(now, settings.offlineThresholdSeconds * 1000L);
        json.put("online", online);
        json.put("status", device.revoked ? "revoked"
                : device.disconnected ? "disconnected" : online ? "online" : "offline");
        json.put("metricsAvailable", device.hasReport()
                && !device.isReportStale(now, settings.offlineThresholdSeconds * 3000L));
        json.put("metricsStale", device.isReportStale(now, settings.offlineThresholdSeconds * 3000L));
        return json;
    }

    private static JSONObject userJson(RemoteUser user) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", user.id);
        json.put("username", user.username);
        json.put("displayName", user.displayName);
        json.put("role", user.role);
        json.put("status", user.status);
        json.put("createdAt", user.createdAt);
        json.put("updatedAt", user.updatedAt);
        json.put("lastLoginAt", user.lastLoginAt);
        json.put("locked", user.lockedUntil > System.currentTimeMillis());
        json.put("grantedPermissions", new JSONArray(user.grantedPermissions));
        json.put("deniedPermissions", new JSONArray(user.deniedPermissions));
        json.put("effectivePermissions", new JSONArray(RemotePermissions.forRole(user.role)));
        return json;
    }

    private static void readPermissionSet(JSONArray array, Set<String> target) {
        target.clear();
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            String permission = array.optString(i, "");
            if (RemotePermissions.isValid(permission)) target.add(permission);
        }
    }

    private static void enforceTransport(SimpleHttpServer.Request req, RemoteSettings settings, boolean publicPath)
            throws DataException {
        if (!settings.enabled) {
            throw DataException.forbidden("remote access is disabled on this device");
        }
        if (settings.requireHttps && !req.secure && !isLoopback(req.remoteIp)) {
            throw DataException.forbidden("HTTPS is required for remote access");
        }
    }

    private static boolean isLoopback(String ip) {
        if (ip == null) return false;
        return ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1");
    }

    private static Caller caller(Context context, SimpleHttpServer.Request req, DataApi.Auth auth,
                                 RemoteSettings settings) throws DataException {
        if (auth == null) return null;
        Caller caller = new Caller();
        caller.actor = "remote";
        if (auth.remoteSession != null) {
            RemoteSession session = auth.remoteSession;
            caller.session = session;
            caller.actor = session.subjectType + ":" + session.subjectId;
            if (RemoteSession.SUBJECT_USER.equals(session.subjectType)) {
                RemoteUser user = RemoteUserStore.get(context).get(session.subjectId);
                if (user == null || !user.isActive()) return null;
                caller.user = user;
                caller.permissions.addAll(RemotePermissions.forRole(user.role));
                caller.permissions.addAll(user.grantedPermissions);
                caller.permissions.removeAll(user.deniedPermissions);
            } else if (RemoteSession.SUBJECT_DEVICE.equals(session.subjectType)) {
                RemoteDevice device = RemoteDeviceStore.get(context).get(session.subjectId);
                if (device == null || device.revoked || device.disconnected) return null;
                caller.device = device;
                caller.permissions.addAll(RemotePermissions.forRole(RemoteRole.DEVICE));
            } else {
                return null;
            }
            caller.permissions.removeAll(session.deniedPermissions);
            caller.permissions.addAll(session.grantedPermissions);
            caller.permissions.removeAll(session.deniedPermissions);
            return caller;
        }
        if (!auth.admin) return null;
        caller.admin = true;
        caller.permissions.addAll(RemotePermissions.ALL);
        return caller;
    }

    private static void require(Context context, SimpleHttpServer.Request req, Caller caller, String permission)
            throws DataException {
        if (caller.has(permission)) return;
        audit(context, req, RemoteAuditEntry.ACCESS_DENIED, caller, permission,
                "missing " + permission, false);
        throw DataException.forbidden("this credential lacks the " + permission + " permission");
    }

    private static void audit(Context context, SimpleHttpServer.Request req, String event, Caller caller,
                              String subject, String details, boolean allowed) {
        RemoteAuditStore.get(context).append(event, caller == null ? "anonymous" : caller.actor,
                subject, req.remoteIp, details, allowed);
    }

    private static int boundedInt(String value, int min, int max, int fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) return fallback;
            return parsed;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String decode(String value) {
        if (value == null) return "";
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String qrPayload(SimpleHttpServer.Request req, String code) {
        String scheme = req.secure ? "https" : "http";
        return "phoneserver://pair?scheme=" + scheme + "&host=" + safe(req.headers.get("host")) + "&code=" + code;
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replaceAll("[^A-Za-z0-9.:\\-\\[\\]]", "");
    }

    private static final class Caller {
        boolean admin;
        RemoteSession session;
        RemoteUser user;
        RemoteDevice device;
        String actor = "remote";
        final Set<String> permissions = new LinkedHashSet<>();

        boolean has(String permission) {
            if (admin) return true;
            if (session != null && session.deniedPermissions.contains(permission)) return false;
            return permissions.contains(permission);
        }
    }

    private static final class LoginAttempts {
        int failed;
        long firstFailureAt;

        boolean locked(long now) {
            return failed >= 10 && firstFailureAt > 0 && now - firstFailureAt < 10 * 60_000L;
        }
    }
}

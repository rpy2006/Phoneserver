package com.yadaventerprise.phoneserver;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Talks to another phone running PhoneServer over plain HTTP. All methods here
 * do blocking network I/O and must be called from a background thread.
 */
public class RemoteApiClient {

    public static class ApiException extends Exception {
        public ApiException(String message) {
            super(message);
        }
    }

    private static final String USER_AGENT = "PhoneServer-App/1.0 (Android)";

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** Quick reachability check - hits the public /status endpoint with a short timeout. No password needed. */
    public static boolean isOnline(ServerProfile profile) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(profile.baseUrl() + "/status");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2500);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setReadTimeout(2500);
            int code = conn.getResponseCode();
            return code == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static StatsSnapshot getStats(ServerProfile profile) throws ApiException {
        JSONObject json = getJson(profile, "/api/stats");
        try {
            StatsSnapshot snap = new StatsSnapshot();
            snap.requestCount = json.optLong("requestCount", 0);
            snap.bytesUp = json.optLong("bytesUp", 0);
            snap.bytesDown = json.optLong("bytesDown", 0);
            snap.activeClients = json.optInt("activeClients", 0);
            snap.ramUsedPercent = json.optInt("ramUsedPercent", 0);
            snap.appCpuPercent = json.optInt("appCpuPercent", 0);
            snap.uptimeMillis = json.optLong("uptimeMillis", 0);

            JSONArray clientsArr = json.optJSONArray("clients");
            if (clientsArr != null) {
                for (int i = 0; i < clientsArr.length(); i++) {
                    JSONObject c = clientsArr.getJSONObject(i);
                    StatsSnapshot.ClientEntry entry = new StatsSnapshot.ClientEntry();
                    entry.ip = c.optString("ip");
                    entry.os = c.optString("os");
                    entry.browser = c.optString("browser");
                    entry.lastSeen = c.optLong("lastSeen");
                    snap.clients.add(entry);
                }
            }

            JSONArray endpointsArr = json.optJSONArray("topEndpoints");
            if (endpointsArr != null) {
                for (int i = 0; i < endpointsArr.length(); i++) {
                    JSONObject e = endpointsArr.getJSONObject(i);
                    StatsSnapshot.EndpointEntry entry = new StatsSnapshot.EndpointEntry();
                    entry.path = e.optString("path");
                    entry.count = e.optInt("count");
                    snap.topEndpoints.add(entry);
                }
            }

            JSONArray activityArr = json.optJSONArray("recentActivity");
            if (activityArr != null) {
                for (int i = 0; i < activityArr.length(); i++) {
                    JSONObject a = activityArr.getJSONObject(i);
                    StatsSnapshot.ActivityEntry entry = new StatsSnapshot.ActivityEntry();
                    entry.type = a.optString("type");
                    entry.message = a.optString("message");
                    entry.time = a.optLong("time");
                    snap.recentActivity.add(entry);
                }
            }

            return snap;
        } catch (Exception e) {
            throw new ApiException("Unexpected stats response from server");
        }
    }

    public static List<RemoteEntry> browse(ServerProfile profile, String path) throws ApiException {
        String query = "path=" + encode(path == null ? "" : path);
        JSONObject json = getJson(profile, "/api/browse?" + query);
        List<RemoteEntry> result = new ArrayList<>();
        try {
            JSONArray entries = json.getJSONArray("entries");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                result.add(new RemoteEntry(
                        e.getString("name"),
                        e.getBoolean("isDir"),
                        e.optLong("size", 0),
                        e.optLong("modified", 0)
                ));
            }
        } catch (Exception e) {
            throw new ApiException("Unexpected response from server");
        }
        return result;
    }

    public static void mkdir(ServerProfile profile, String path, String name) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("path", path == null ? "" : path);
            body.put("name", name);
            postJson(profile, "/api/mkdir", body);
        } catch (Exception e) {
            throw new ApiException(e.getMessage() != null ? e.getMessage() : "Failed to create folder");
        }
    }

    public static void delete(ServerProfile profile, String path) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("path", path);
            postJson(profile, "/api/delete", body);
        } catch (Exception e) {
            throw new ApiException(e.getMessage() != null ? e.getMessage() : "Delete failed");
        }
    }

    public static void rename(ServerProfile profile, String path, String newName) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("path", path);
            body.put("newName", newName);
            postJson(profile, "/api/rename", body);
        } catch (Exception e) {
            throw new ApiException(e.getMessage() != null ? e.getMessage() : "Rename failed");
        }
    }

    public static void upload(ServerProfile profile, String targetPath, ContentResolver resolver, Uri fileUri, String filename) throws ApiException {
        String boundary = "PhoneServerBoundary" + UUID.randomUUID();
        HttpURLConnection conn = null;
        try {
            byte[] header = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

            ByteArrayOutputStream fileBuffer = new ByteArrayOutputStream();
            try (InputStream in = resolver.openInputStream(fileUri)) {
                if (in == null) throw new IOException("Could not read file");
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    fileBuffer.write(buffer, 0, len);
                }
            }
            byte[] fileBytes = fileBuffer.toByteArray();

            int totalLength = header.length + fileBytes.length + footer.length;

            URL url = new URL(profile.baseUrl() + "/api/upload?path=" + encode(targetPath == null ? "" : targetPath));
            conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (profile.password != null && !profile.password.isEmpty()) {
                conn.setRequestProperty("X-Auth-Password", profile.password);
            }
            // Fixed-length streaming so the request carries a real Content-Length
            // header - our server reads the body by that header and does not
            // understand chunked transfer encoding.
            conn.setFixedLengthStreamingMode(totalLength);

            try (OutputStream out = conn.getOutputStream()) {
                out.write(header);
                out.write(fileBytes);
                out.write(footer);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new ApiException("Upload failed (server responded " + code + ")");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Upload failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Downloads a remote file into destFile. path uses "/" separators, e.g. "photos/trip.jpg". */
    public static void download(ServerProfile profile, String path, File destFile) throws ApiException {
        HttpURLConnection conn = null;
        try {
            String[] segments = path.split("/");
            StringBuilder encodedPath = new StringBuilder();
            for (String seg : segments) {
                if (seg.isEmpty()) continue;
                if (encodedPath.length() > 0) encodedPath.append("/");
                encodedPath.append(encode(seg));
            }
            String queryAuth = (profile.password != null && !profile.password.isEmpty())
                    ? "?pw=" + encode(profile.password) : "";
            URL url = new URL(profile.baseUrl() + "/files/" + encodedPath + queryAuth);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setReadTimeout(30000);
            if (profile.password != null && !profile.password.isEmpty()) {
                conn.setRequestProperty("X-Auth-Password", profile.password);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new ApiException("Download failed (server responded " + code + ")");
            }

            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Download failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---- low-level helpers ----

    private static JSONObject getJson(ServerProfile profile, String pathAndQuery) throws ApiException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(profile.baseUrl() + pathAndQuery);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setReadTimeout(15000);
            if (profile.password != null && !profile.password.isEmpty()) {
                conn.setRequestProperty("X-Auth-Password", profile.password);
            }
            int code = conn.getResponseCode();
            InputStream stream = code == 200 ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(stream);
            if (code == 401) throw new ApiException("Wrong password");
            if (code != 200) throw new ApiException("Server responded " + code);
            return new JSONObject(text);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Could not reach server: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void postJson(ServerProfile profile, String path, JSONObject body) throws ApiException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(profile.baseUrl() + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/json");
            if (profile.password != null && !profile.password.isEmpty()) {
                conn.setRequestProperty("X-Auth-Password", profile.password);
            }
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream stream = code == 200 ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(stream);
            if (code == 401) throw new ApiException("Wrong password");
            if (code != 200) {
                String message = "Server responded " + code;
                try {
                    JSONObject err = new JSONObject(text);
                    if (err.has("error")) message = err.getString("error");
                } catch (Exception ignored) {
                }
                throw new ApiException(message);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Could not reach server: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int len;
        while ((len = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, len);
        }
        return buffer.toString("UTF-8");
    }
}

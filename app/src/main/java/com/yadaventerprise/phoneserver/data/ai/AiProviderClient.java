package com.yadaventerprise.phoneserver.data.ai;

import com.yadaventerprise.phoneserver.ai.LlmEngine;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AiProviderClient {
    private AiProviderClient() {}

    public static String complete(AiSettings settings, String system, String user) throws Exception {
        if (settings == null || !settings.enabled) throw new IllegalStateException("AI is disabled in settings");
        if (AiSettings.PROVIDER_LOCAL.equals(settings.provider) || settings.localInference) {
            if (!LlmEngine.get().isNativeLibraryAvailable() || !LlmEngine.get().isModelLoaded()) {
                throw new IllegalStateException("Local model is not loaded");
            }
            String prompt = "[SYSTEM]\n" + system + "\n[/SYSTEM]\n[USER]\n" + user + "\n[/USER]\n[ASSISTANT]\n";
            return LlmEngine.get().generateBlocking(prompt);
        }
        if (!AiSettings.PROVIDER_OPENAI_COMPATIBLE.equals(settings.provider)) throw new IllegalStateException("No AI provider configured");
        if (settings.endpoint == null || settings.endpoint.trim().isEmpty()) throw new IllegalStateException("AI endpoint is required");
        if (settings.apiKey == null || settings.apiKey.trim().isEmpty()) throw new IllegalStateException("AI API key is required");
        URL url = new URL(settings.endpoint.trim());
        String protocol = url.getProtocol();
        if (!"https".equalsIgnoreCase(protocol) && !"http".equalsIgnoreCase(protocol)) throw new IllegalStateException("Unsupported AI endpoint protocol");
        if ("http".equalsIgnoreCase(protocol)) {
            String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
            boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("10.0.2.2");
            if (!local) throw new IllegalStateException("External AI endpoint must use HTTPS");
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(settings.timeoutSeconds * 1000);
        conn.setReadTimeout(settings.timeoutSeconds * 1000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + settings.apiKey.trim());
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        JSONObject body = new JSONObject();
        if (settings.model != null && !settings.model.trim().isEmpty()) body.put("model", settings.model.trim());
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", system));
        messages.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", messages);
        body.put("temperature", 0.2);
        try (OutputStream os = conn.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sb.append(line);
        }
        if (code < 200 || code >= 300) throw new IllegalStateException("AI provider error: HTTP " + code);
        JSONObject resp = new JSONObject(sb.toString());
        JSONArray choices = resp.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject msg = choices.optJSONObject(0).optJSONObject("message");
            if (msg != null) return msg.optString("content", "").trim();
        }
        return resp.optString("output_text", "").trim();
    }
}

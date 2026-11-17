package com.yadaventerprise.phoneserver.ui.docs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONObject;

final class DocsHttp {

    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_BODY = 200_000;

    private DocsHttp() {
    }

    static JSONObject request(String method, String urlValue, JSONObject headers, String body) throws Exception {
        String verb = method == null ? "GET" : method.toUpperCase(Locale.US);
        if (!verb.matches("GET|POST|PUT|PATCH|DELETE|HEAD")) throw new IllegalArgumentException("method not allowed");
        URL url = new URL(urlValue);
        String protocol = url.getProtocol().toLowerCase(Locale.US);
        if (!protocol.equals("http") && !protocol.equals("https")) {
            throw new IllegalArgumentException("only http and https are supported");
        }
        int port = url.getPort() > 0 ? url.getPort() : (protocol.equals("https") ? 443 : 80);
        Socket socket = connect(url.getHost(), port, protocol);
        try {
            byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            if (bodyBytes.length > 2_000_000) throw new IllegalArgumentException("request body is too large");
            String path = url.getFile();
            if (path == null || path.isEmpty()) path = "/";
            StringBuilder request = new StringBuilder();
            request.append(verb).append(' ').append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(url.getHost());
            if ((protocol.equals("https") && port != 443) || (protocol.equals("http") && port != 80)) {
                request.append(':').append(port);
            }
            request.append("\r\n");
            request.append("Connection: close\r\n");
            request.append("Accept: application/json, text/plain, */*\r\n");
            request.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            if (headers != null) {
                java.util.Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = headers.optString(key, "");
                    if (!key.matches("[A-Za-z0-9-]{1,80}") || value.contains("\r") || value.contains("\n")) continue;
                    if (key.equalsIgnoreCase("Host") || key.equalsIgnoreCase("Content-Length")) continue;
                    request.append(key).append(": ").append(value).append("\r\n");
                }
            }
            request.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (bodyBytes.length > 0) out.write(bodyBytes);
            out.flush();

            InputStream in = socket.getInputStream();
            String statusLine = readLine(in);
            if (statusLine == null) throw new IOException("empty response");
            String[] statusParts = statusLine.split(" ", 3);
            int status = Integer.parseInt(statusParts[1]);
            String statusText = statusParts.length > 2 ? statusParts[2] : "";
            JSONObject responseHeaders = new JSONObject();
            String line;
            long contentLength = -1;
            boolean chunked = false;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (key.equalsIgnoreCase("Content-Length")) contentLength = Long.parseLong(value);
                if (key.equalsIgnoreCase("Transfer-Encoding") && value.toLowerCase(Locale.US).contains("chunked")) {
                    chunked = true;
                }
                if (responseHeaders.length() < 30) responseHeaders.put(key, value);
            }
            byte[] responseBody = chunked
                    ? readChunked(in)
                    : readBody(in, contentLength);
            String text = new String(responseBody, StandardCharsets.UTF_8);
            JSONObject result = new JSONObject();
            result.put("status", status);
            result.put("statusText", statusText);
            result.put("headers", responseHeaders);
            result.put("body", text);
            result.put("truncated", responseBody.length >= MAX_BODY);
            return result;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Socket connect(String host, int port, String protocol) throws IOException {
        if (protocol.equals("https")) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket();
            ssl.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            ssl.setSoTimeout(TIMEOUT_MS);
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(parameters);
            ssl.startHandshake();
            return ssl;
        }
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
        socket.setSoTimeout(TIMEOUT_MS);
        return socket;
    }

    private static byte[] readBody(InputStream in, long length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (length >= 0) {
            readInto(in, out, (int) Math.min(length, MAX_BODY));
            return out.toByteArray();
        }
        readInto(in, out, MAX_BODY);
        return out.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() < MAX_BODY) {
            String line = readLine(in);
            if (line == null) break;
            int semi = line.indexOf(';');
            if (semi >= 0) line = line.substring(0, semi);
            int size;
            try {
                size = Integer.parseInt(line.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("invalid chunk");
            }
            if (size == 0) break;
            readInto(in, out, Math.min(size, MAX_BODY - out.size()));
            if (out.size() >= MAX_BODY) break;
            readLine(in);
        }
        return out.toByteArray();
    }

    private static void readInto(InputStream in, ByteArrayOutputStream out, int limit) throws IOException {
        byte[] buffer = new byte[8192];
        int remaining = limit;
        while (remaining > 0) {
            int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) >= 0) {
            if (value == '\n') break;
            if (value != '\r') out.write(value);
        }
        if (value < 0 && out.size() == 0) return null;
        return out.toString(StandardCharsets.ISO_8859_1.name());
    }
}

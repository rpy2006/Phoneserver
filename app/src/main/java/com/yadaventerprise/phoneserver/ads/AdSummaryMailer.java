package com.yadaventerprise.phoneserver.ads;

import android.util.Base64;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONObject;

/** Sends the once-a-day operational summary through a configured SMTP server, with no mail library. */
final class AdSummaryMailer {
    private AdSummaryMailer() {}

    static synchronized void maybeSend(AdDb db) {
        if (!"1".equals(db.setting("email_enabled", "0"))) return;
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        int hour = now.get(Calendar.HOUR_OF_DAY);
        if (hour < db.settingLong("email_hour_utc", 7)) return;
        long day = AdDb.today() - 1;
        JSONObject previous = db.find(AdDb.SUMMARIES, x -> x.optLong("day") == day);
        if (previous != null) {
            if ("sent".equals(previous.optString("status"))) return;
            if (System.currentTimeMillis() - previous.optLong("ts") < 6 * 3600_000L) return;
        }
        try {
            deliver(db, day);
        } catch (RuntimeException e) {
            record(db, day, "failed", "", e.getMessage() == null ? "send failed" : e.getMessage(), new long[7]);
            android.util.Log.w("AdNet", "summary mail failed", e);
        }
    }

    static AdHttp.Res sendNow(AdHttp.Req r, AdSecurity.User admin, AdDb db) {
        JSONObject body = r.body.length == 0 ? new JSONObject() : AdHttp.body(r);
        long day = AdDb.today() - 1;
        if (body.has("day")) {
            try {
                day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(body.optString("day")).getTime() / 86_400_000L;
            } catch (java.text.ParseException e) {
                throw AdHttp.bad("day must be a date like 2026-11-14");
            }
        }
        try {
            long[] totals = deliver(db, day);
            db.audit(admin.id, "summary.send", AdReports.dayString(day));
            return AdHttp.ok(AdHttp.obj("ok", true, "day", AdReports.dayString(day), "totals", totalsJson(totals)));
        } catch (RuntimeException e) {
            record(db, day, "failed", "", e.getMessage() == null ? "send failed" : e.getMessage(), new long[7]);
            throw new AdHttp.HttpError(502, "Could not send email: " + e.getMessage());
        }
    }

    static JSONObject history(AdDb db) {
        JSONArray out = new JSONArray();
        List<JSONObject> rows = db.list(AdDb.SUMMARIES, x -> true);
        rows.sort((a, b) -> Long.compare(b.optLong("day"), a.optLong("day")));
        for (int i = 0; i < rows.size() && i < 31; i++) {
            JSONObject row = rows.get(i);
            AdHttp.put(row, "date", AdReports.dayString(row.optLong("day")));
            out.put(row);
        }
        return AdHttp.obj("email_enabled", "1".equals(db.setting("email_enabled", "0")),
                "recipients", db.setting("email_recipients", ""),
                "hour_utc", db.settingLong("email_hour_utc", 7),
                "smtp_configured", !db.setting("smtp_host", "").isEmpty(),
                "history", out);
    }

    private static long[] deliver(AdDb db, long day) {
        String host = db.setting("smtp_host", "").trim();
        if (host.isEmpty()) throw new IllegalStateException("SMTP host is not configured");
        if (!host.matches("[A-Za-z0-9.:\\[\\]-]{1,200}")) throw new IllegalStateException("SMTP host is invalid");
        int port = (int) db.settingLong("smtp_port", 587);
        String security = db.setting("smtp_security", "starttls");
        List<String> recipients = recipients(db);
        if (recipients.isEmpty()) throw new IllegalStateException("No email recipients are configured");
        String from = db.setting("smtp_from", "").trim();
        String username = db.setting("smtp_username", "");
        if (from.isEmpty()) from = username;
        if (!AdSecurity.isEmail(from)) throw new IllegalStateException("A valid SMTP From address is required");
        String password = db.setting("smtp_password", "");

        long[] totals = totals(db, day);
        String message = message(db, from, recipients, day, totals);
        smtpSend(host, port, security, username, password, from, recipients, message);
        record(db, day, "sent", join(recipients, ";"), "", totals);
        return totals;
    }

    private static List<String> recipients(AdDb db) {
        List<String> out = new ArrayList<>();
        for (String addr : db.setting("email_recipients", "").split("[,;\\s]+")) {
            if (!addr.isEmpty() && AdSecurity.isEmail(addr) && !out.contains(addr)) out.add(addr);
        }
        return out;
    }

    private static long[] totals(AdDb db, long day) {
        long[] t = new long[7];
        for (JSONObject s : db.list(AdDb.STATS, x -> x.optLong("day") == day)) {
            t[0] += s.optLong("served");
            t[1] += s.optLong("imps");
            t[2] += s.optLong("clicks");
            t[3] += s.optLong("conversions");
            t[4] += s.optLong("spend_micros");
            t[5] += s.optLong("pub_micros");
            t[6] += s.optLong("invalid_imps") + s.optLong("invalid_clicks");
        }
        return t;
    }

    private static JSONObject totalsJson(long[] t) {
        return AdHttp.obj("served", t[0], "imps", t[1], "clicks", t[2], "conversions", t[3],
                "spend_micros", t[4], "earnings_micros", t[5], "invalid_events", t[6]);
    }

    private static String message(AdDb db, String from, List<String> recipients, long day, long[] t) {
        String currency = db.setting("currency", "USD");
        long campaigns = db.count(AdDb.CAMPAIGNS, x -> "approved".equals(x.optString("status")));
        long sites = db.count(AdDb.SITES, x -> "approved".equals(x.optString("status")));
        String body = "Ad Network daily summary\r\n"
                + "Date: " + AdReports.dayString(day) + " (UTC)\r\n\r\n"
                + "Impressions: " + t[1] + "\r\n"
                + "Clicks: " + t[2] + "\r\n"
                + "Conversions: " + t[3] + "\r\n"
                + "Advertiser spend: " + currency + " " + money(t[4]) + "\r\n"
                + "Publisher earnings: " + currency + " " + money(t[5]) + "\r\n"
                + "Invalid impressions/clicks: " + t[6] + "\r\n"
                + "Active campaigns: " + campaigns + "\r\n"
                + "Active sites: " + sites + "\r\n\r\n"
                + "This is an automated message from PhoneServer.\r\n";
        String date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(new Date());
        return "From: " + from + "\r\n"
                + "To: " + join(recipients, ", ") + "\r\n"
                + "Subject: Ad network daily summary " + AdReports.dayString(day) + "\r\n"
                + "Date: " + date + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n\r\n"
                + body;
    }

    private static String money(long micros) {
        return String.format(Locale.US, "%.4f", micros / 1_000_000.0);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(separator);
            sb.append(v);
        }
        return sb.toString();
    }

    private static void record(AdDb db, long day, String status, String recipients, String error, long[] t) {
        JSONObject row = AdHttp.obj("day", day, "ts", System.currentTimeMillis(), "status", status,
                "recipients", recipients, "error", AdSecurity.clean(error).length() > 300
                        ? AdSecurity.clean(error).substring(0, 300) : AdSecurity.clean(error),
                "served", t[0], "imps", t[1], "clicks", t[2], "conversions", t[3],
                "spend_micros", t[4], "earnings_micros", t[5], "invalid_events", t[6]);
        db.transaction(() -> {
            db.deleteWhere(AdDb.SUMMARIES, x -> x.optLong("day") == day);
            db.insert(AdDb.SUMMARIES, row);
        });
    }

    // ---------------- minimal SMTP client ----------------

    private static void smtpSend(String host, int port, String security, String username, String password,
                                 String from, List<String> recipients, String message) {
        Socket socket = null;
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            if ("tls".equals(security)) {
                SSLSocket ssl = (SSLSocket) factory.createSocket();
                ssl.connect(new InetSocketAddress(host, port), 15_000);
                ssl.setSoTimeout(20_000);
                secure(ssl, host);
                socket = ssl;
            } else {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 15_000);
                socket.setSoTimeout(20_000);
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
            expect(in, 220);
            String hostname = InetAddress.getLocalHost().getHostName();
            if (!hostname.matches("[A-Za-z0-9.-]{1,100}")) hostname = "localhost";
            command(in, out, "EHLO " + hostname, 250);
            if ("starttls".equals(security)) {
                command(in, out, "STARTTLS", 220);
                SSLSocket ssl = (SSLSocket) factory.createSocket(socket, host, port, true);
                secure(ssl, host);
                socket = ssl;
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
                command(in, out, "EHLO " + hostname, 250);
            }
            if (!username.isEmpty()) {
                command(in, out, "AUTH LOGIN", 334);
                command(in, out, Base64.encodeToString(username.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP), 334);
                command(in, out, Base64.encodeToString(password.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP), 235);
            }
            command(in, out, "MAIL FROM:<" + from + ">", 250);
            for (String rcpt : recipients) command(in, out, "RCPT TO:<" + rcpt + ">", 250, 251);
            command(in, out, "DATA", 354);
            for (String line : message.split("\\r?\\n", -1)) {
                if (line.startsWith(".")) out.write('.');
                out.write(line);
                out.write("\r\n");
            }
            out.write(".\r\n");
            out.flush();
            expect(in, 250);
            try {
                command(in, out, "QUIT", 221);
            } catch (RuntimeException ignored) {
            }
        } catch (IOException e) {
            throw new IllegalStateException("SMTP connection failed: " + e.getMessage(), e);
        } finally {
            if (socket != null) try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void secure(SSLSocket socket, String host) throws IOException {
        SSLParameters p = socket.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(p);
        socket.startHandshake();
    }

    private static void command(BufferedReader in, BufferedWriter out, String command, int... expected) throws IOException {
        out.write(command);
        out.write("\r\n");
        out.flush();
        expect(in, expected);
    }

    private static void expect(BufferedReader in, int... expected) throws IOException {
        String response = read(in);
        if (response.length() < 3) throw new IOException("SMTP server closed the connection");
        int code;
        try {
            code = Integer.parseInt(response.substring(0, 3));
        } catch (NumberFormatException e) {
            throw new IOException("Unexpected SMTP response");
        }
        for (int e : expected) if (code == e) return;
        String clean = response.replaceAll("[\\r\\n]+", " ");
        if (clean.length() > 160) clean = clean.substring(0, 160);
        throw new IOException("SMTP error " + code + ": " + clean);
    }

    private static String read(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        do {
            line = in.readLine();
            if (line == null) break;
            sb.append(line).append('\n');
        } while (line.length() >= 4 && line.charAt(3) == '-');
        return sb.toString();
    }
}

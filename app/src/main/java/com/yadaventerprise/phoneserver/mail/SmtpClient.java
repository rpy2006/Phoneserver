package com.yadaventerprise.phoneserver.mail;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLSocket;

public final class SmtpClient {

    private static final int TIMEOUT_MS = 20_000;

    private SmtpClient() {
    }

    public static void verify(MailAccount account, String username, String password) throws MailException {
        MailValidation.validate(account);
        if (username.isEmpty() || password.isEmpty()) throw new MailException("SMTP username and password are required");
        Socket socket = null;
        try {
            socket = MailNet.open(account.smtpHost, account.smtpPort, account.smtpSecurity, TIMEOUT_MS);
            BufferedReader in = reader(socket);
            BufferedWriter out = writer(socket);
            int[] code = expect(in, 220);
            require(code[0] == 220, "SMTP greeting rejected");
            String helo = localName();
            command(in, out, "EHLO " + helo, 250);
            if (MailValidation.security(account.smtpSecurity).equals("starttls")) {
                command(in, out, "STARTTLS", 220);
                socket = MailNet.upgrade(socket, account.smtpHost, account.smtpPort, TIMEOUT_MS);
                in = reader(socket);
                out = writer(socket);
                command(in, out, "EHLO " + helo, 250);
            }
            authenticate(in, out, username, password);
            command(in, out, "QUIT", 221);
        } catch (IOException e) {
            throw new MailException("SMTP connection failed", 502);
        } finally {
            close(socket);
        }
    }

    public static void send(MailAccount account, String username, String password,
                            List<String> recipients, byte[] message) throws MailException {
        MailValidation.validate(account);
        if (recipients == null || recipients.isEmpty()) throw new MailException("no SMTP recipients");
        if (message == null || message.length == 0) throw new MailException("message is empty");
        Socket socket = null;
        try {
            socket = MailNet.open(account.smtpHost, account.smtpPort, account.smtpSecurity, TIMEOUT_MS);
            BufferedReader in = reader(socket);
            BufferedWriter out = writer(socket);
            int[] greeting = expect(in, 220);
            require(greeting[0] == 220, "SMTP greeting rejected");
            String helo = localName();
            command(in, out, "EHLO " + helo, 250);
            if (MailValidation.security(account.smtpSecurity).equals("starttls")) {
                command(in, out, "STARTTLS", 220);
                socket = MailNet.upgrade(socket, account.smtpHost, account.smtpPort, TIMEOUT_MS);
                in = reader(socket);
                out = writer(socket);
                command(in, out, "EHLO " + helo, 250);
            }
            if (!username.isEmpty() && !password.isEmpty()) authenticate(in, out, username, password);
            command(in, out, "MAIL FROM:<" + account.email + ">", 250);
            for (String recipient : recipients) command(in, out, "RCPT TO:<" + recipient + ">", 250, 251);
            command(in, out, "DATA", 354);
            writeData(out, message);
            expect(in, 250);
            try {
                command(in, out, "QUIT", 221);
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            throw new MailException("SMTP send failed", 502);
        } finally {
            close(socket);
        }
    }

    static void authenticate(BufferedReader in, BufferedWriter out, String username, String password)
            throws IOException, MailException {
        command(in, out, "AUTH LOGIN", 334);
        command(in, out, MailBase64.encode(username.getBytes(StandardCharsets.UTF_8)), 334);
        command(in, out, MailBase64.encode(password.getBytes(StandardCharsets.UTF_8)), 235);
    }

    static void writeData(BufferedWriter out, byte[] message) throws IOException {
        String text = new String(message, StandardCharsets.ISO_8859_1);
        if (!text.endsWith("\n")) text += "\r\n";
        for (String line : text.split("\\r?\\n")) {
            if (line.startsWith(".")) out.write('.');
            out.write(line);
            out.write("\r\n");
        }
        out.write(".\r\n");
        out.flush();
    }

    static int[] command(BufferedReader in, BufferedWriter out, String command, int... expected)
            throws IOException, MailException {
        out.write(command);
        out.write("\r\n");
        out.flush();
        return expect(in, expected);
    }

    static int[] expect(BufferedReader in, int... expected) throws IOException, MailException {
        StringBuilder response = new StringBuilder();
        String line;
        int code = -1;
        do {
            line = in.readLine();
            if (line == null) throw new IOException("server closed connection");
            if (code < 0 && line.length() >= 3) {
                try {
                    code = Integer.parseInt(line.substring(0, 3));
                } catch (NumberFormatException ignored) {
                    code = -1;
                }
            }
            response.append(line).append('\n');
        } while (line.length() >= 4 && line.charAt(3) == '-');
        if (expected == null || expected.length == 0) return new int[]{code};
        for (int value : expected) {
            if (code == value) return new int[]{code};
        }
        throw new MailException("SMTP rejected the command (" + code + ")", 502);
    }

    private static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
    }

    private static BufferedWriter writer(Socket socket) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1));
    }

    private static String localName() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            return name.matches("[A-Za-z0-9.-]{1,100}") ? name : "localhost";
        } catch (Exception e) {
            return "localhost";
        }
    }

    private static void require(boolean condition, String message) throws MailException {
        if (!condition) throw new MailException(message, 502);
    }

    private static void close(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}

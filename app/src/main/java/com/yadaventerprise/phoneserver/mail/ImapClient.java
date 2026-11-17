package com.yadaventerprise.phoneserver.mail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ImapClient {

    public static final class Fetched {
        public final String uid;
        public final byte[] message;
        public final boolean seen;
        public final boolean flagged;

        Fetched(String uid, byte[] message, boolean seen, boolean flagged) {
            this.uid = uid;
            this.message = message;
            this.seen = seen;
            this.flagged = flagged;
        }
    }

    private static final int TIMEOUT_MS = 20_000;

    private final Socket socket;
    private final LineReader in;
    private final Writer out;
    private int tagCounter;

    private ImapClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new LineReader(socket.getInputStream());
        this.out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
    }

    public static ImapClient connect(MailAccount account, String username, String password) throws MailException {
        MailValidation.validate(account);
        if (username.isEmpty() || password.isEmpty()) throw new MailException("IMAP username and password are required");
        Socket socket = null;
        try {
            socket = MailNet.open(account.imapHost, account.imapPort, account.imapSecurity, TIMEOUT_MS);
            String greeting = null;
            if (MailValidation.security(account.imapSecurity).equals("starttls")) {
                greeting = new LineReader(socket.getInputStream()).readLine();
                if (greeting == null || !greeting.startsWith("*")) throw new IOException("invalid IMAP greeting");
                OutputStream rawOut = socket.getOutputStream();
                rawOut.write(("A1 STARTTLS\r\n").getBytes(StandardCharsets.US_ASCII));
                rawOut.flush();
                LineReader pre = new LineReader(socket.getInputStream());
                String line;
                boolean ready = false;
                while ((line = pre.readLine()) != null) {
                    if (line.startsWith("+")) {
                        ready = true;
                        break;
                    }
                    if (line.startsWith("A1 ")) break;
                }
                if (!ready) throw new IOException("STARTTLS was rejected");
                socket = MailNet.upgrade(socket, account.imapHost, account.imapPort, TIMEOUT_MS);
            }
            ImapClient client = new ImapClient(socket);
            socket = null;
            if (greeting == null) greeting = client.in.readLine();
            if (greeting == null || !(greeting.startsWith("* OK") || greeting.startsWith("* PREAUTH"))) {
                throw new IOException("IMAP greeting rejected");
            }
            client.command("LOGIN " + quote(username) + " " + quote(password), 250);
            return client;
        } catch (IOException e) {
            throw new MailException("IMAP connection failed", 502);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public void select(String folder) throws MailException {
        if (!MailValidation.isFolderName(folder)) throw new MailException("invalid mail folder");
        try {
            command("SELECT " + quote(folder), 250);
        } catch (IOException e) {
            throw new MailException("mail folder could not be opened", 502);
        }
    }

    public List<String> uids() throws MailException {
        try {
            String tag = nextTag();
            send(tag + " UID SEARCH ALL");
            List<String> lines = readTagged(tag);
            for (String line : lines) {
                if (!line.startsWith("* SEARCH")) continue;
                String[] values = line.substring(8).trim().split("\\s+");
                List<String> result = new ArrayList<>();
                for (String value : values) {
                    if (value.matches("[0-9]{1,12}")) result.add(value);
                }
                return result;
            }
            return new ArrayList<>();
        } catch (IOException e) {
            throw new MailException("mail search failed", 502);
        }
    }

    public Fetched fetch(String uid) throws MailException {
        if (uid == null || !uid.matches("[0-9]{1,12}")) throw new MailException("invalid message identifier");
        try {
            String tag = nextTag();
            send(tag + " UID FETCH " + uid + " (FLAGS BODY.PEEK[])");
            boolean seen = false;
            boolean flagged = false;
            byte[] message = null;
            while (true) {
                String line = in.readLine();
                if (line == null) throw new IOException("IMAP connection closed");
                if (line.startsWith("* ") && line.toUpperCase(Locale.US).contains(" FETCH ")) {
                    String upper = line.toUpperCase(Locale.US);
                    seen = upper.contains("\\SEEN");
                    flagged = upper.contains("\\FLAGGED");
                    int literalStart = line.lastIndexOf('{');
                    int literalEnd = line.lastIndexOf('}');
                    if (literalStart > 0 && literalEnd > literalStart) {
                        int length;
                        try {
                            length = Integer.parseInt(line.substring(literalStart + 1, literalEnd));
                        } catch (NumberFormatException e) {
                            throw new IOException("invalid IMAP literal");
                        }
                        if (length < 0 || length > 20_000_000) throw new IOException("message is too large");
                        message = readExactly(length);
                        consumeLiteralEnding();
                    }
                    continue;
                }
                if (line.startsWith(tag + " ")) {
                    requireStatus(line, tag);
                    break;
                }
            }
            if (message == null) throw new IOException("message body was not returned");
            return new Fetched(uid, message, seen, flagged);
        } catch (IOException e) {
            throw new MailException("message could not be downloaded", 502);
        }
    }

    public void markSeen(String uid) throws MailException {
        if (uid == null || !uid.matches("[0-9]{1,12}")) return;
        try {
            command(nextTag() + " UID STORE " + uid + " +FLAGS (\\Seen)", 250);
        } catch (IOException e) {
            throw new MailException("read status could not be updated", 502);
        }
    }

    public void append(String folder, byte[] message) throws MailException {
        if (!MailValidation.isFolderName(folder)) throw new MailException("invalid mail folder");
        if (message == null || message.length == 0) return;
        try {
            String tag = nextTag();
            send(tag + " APPEND " + quote(folder) + " (\\Seen) {" + message.length + "}");
            boolean ready = false;
            while (true) {
                String line = in.readLine();
                if (line == null) throw new IOException("IMAP connection closed");
                if (line.startsWith("+")) {
                    ready = true;
                    break;
                }
                if (line.startsWith(tag + " ")) {
                    requireStatus(line, tag);
                    return;
                }
            }
            if (!ready) throw new IOException("IMAP did not accept the message");
            out.write(new String(message, StandardCharsets.ISO_8859_1));
            out.write("\r\n");
            out.flush();
            List<String> lines = readTagged(tag);
            if (lines.isEmpty()) throw new IOException("append failed");
        } catch (IOException e) {
            throw new MailException("message could not be saved on the server", 502);
        }
    }

    public void close() {
        try {
            command("LOGOUT", 250);
        } catch (Exception ignored) {
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void command(String command, int... expectedCodes) throws IOException, MailException {
        String tag = nextTag();
        send(tag + " " + command);
        readTagged(tag);
    }

    private void send(String line) throws IOException {
        out.write(line);
        out.write("\r\n");
        out.flush();
    }

    private List<String> readTagged(String tag) throws IOException, MailException {
        List<String> lines = new ArrayList<>();
        while (true) {
            String line = in.readLine();
            if (line == null) throw new IOException("IMAP connection closed");
            if (line.startsWith(tag + " ")) {
                requireStatus(line, tag);
                return lines;
            }
            lines.add(line);
        }
    }

    private void requireStatus(String line, String tag) throws MailException {
        String rest = line.substring(tag.length()).trim().toUpperCase(Locale.US);
        if (rest.startsWith("OK")) return;
        throw new MailException("IMAP command failed (" + rest.split("\\s+", 2)[0] + ")", 502);
    }

    private byte[] readExactly(int length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        byte[] buffer = new byte[8192];
        int remaining = length;
        while (remaining > 0) {
            int read = socket.getInputStream().read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("IMAP connection closed");
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toByteArray();
    }

    private void consumeLiteralEnding() throws IOException {
        InputStream stream = socket.getInputStream();
        int first = stream.read();
        if (first == '\r') {
            int second = stream.read();
            if (second != '\n') throw new IOException("invalid IMAP literal ending");
        } else if (first != '\n') {
            throw new IOException("invalid IMAP literal ending");
        }
    }

    private String nextTag() {
        tagCounter++;
        return "P" + tagCounter;
    }

    private static String quote(String value) {
        String clean = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + clean + "\"";
    }

    private static final class LineReader {
        private final InputStream stream;

        LineReader(InputStream stream) {
            this.stream = stream;
        }

        String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(256);
            int value;
            while ((value = stream.read()) >= 0) {
                if (value == '\n') break;
                if (value != '\r') line.write(value);
            }
            if (value < 0 && line.size() == 0) return null;
            return line.toString(StandardCharsets.US_ASCII.name());
        }
    }
}

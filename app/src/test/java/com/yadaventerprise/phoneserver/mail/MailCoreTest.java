package com.yadaventerprise.phoneserver.mail;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;

public class MailCoreTest {

    @Test
    public void base64RoundTripsBinaryData() {
        byte[] data = new byte[]{0, 1, 2, 127, -1, -32, 65, 66};
        assertArrayEquals(data, MailBase64.decode(MailBase64.encode(data)));
    }

    @Test
    public void validationRejectsHeaderInjectionAndUnsafeFolders() {
        assertFalse(MailValidation.isHost("smtp.example.com\r\nRCPT TO:<victim@example.com>"));
        assertFalse(MailValidation.isEmail("not-an-email"));
        assertFalse(MailValidation.isFolderName("../private"));
        assertEquals("tls", MailValidation.security("SSL"));
        assertEquals("starttls", MailValidation.security("STARTTLS"));
        assertEquals("", MailValidation.security("none"));
    }

    @Test
    public void cryptoRoundTripsWithAesGcm() throws Exception {
        SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
        byte[] encrypted = MailCrypto.encrypt(key, "secret".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8), MailCrypto.decrypt(key, encrypted));
    }

    @Test
    public void mimeBuildAndParsePreservesUnicodeAndBody() throws Exception {
        MailAccount account = account();
        MailCompose compose = new MailCompose();
        compose.to = "you@example.net";
        compose.subject = "Grüße";
        compose.text = "Hello UTF-8 ✓";

        MailMime.Parsed parsed = MailMime.parse(MailMime.build(account, compose));

        assertEquals("Grüße", parsed.subject);
        assertEquals("Hello UTF-8 ✓", parsed.text.trim());
        assertEquals("you@example.net", parsed.to);
    }

    @Test
    public void htmlSanitizerRemovesScriptsAndDangerousAttributes() {
        String html = "<p onclick=\"bad()\">hello<script>alert(1)</script>"
                + "<a href=\"javascript:bad()\">link</a></p>";
        String safe = MailHtmlSanitizer.sanitize(html);
        assertFalse(safe.contains("script"));
        assertFalse(safe.contains("onclick"));
        assertFalse(safe.contains("javascript:"));
        assertTrue(safe.contains("hello"));
    }

    @Test
    public void smtpDotStuffingProtectsMessageTerminator() throws Exception {
        StringWriter target = new StringWriter();
        BufferedWriter writer = new BufferedWriter(target);
        SmtpClient.writeData(writer, ".hidden\r\nnormal\r\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("..hidden\r\nnormal\r\n.\r\n", target.toString());
    }

    @Test
    public void smtpResponseParserAcceptsMultilineReplies() throws Exception {
        BufferedReader reader = new BufferedReader(new StringReader("250-first\r\n250 second\r\n"));
        assertEquals(250, SmtpClient.expect(reader, 250)[0]);
    }

    private static MailAccount account() {
        MailAccount account = new MailAccount();
        account.name = "Test";
        account.email = "me@example.com";
        account.username = "me@example.com";
        account.smtpHost = "smtp.example.com";
        account.imapHost = "imap.example.com";
        account.smtpSecurity = "starttls";
        account.imapSecurity = "tls";
        return account;
    }
}

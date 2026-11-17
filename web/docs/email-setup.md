# Add an email account and set up mail

PhoneServer connects to an external SMTP/IMAP provider, such as a VPS mail server, cPanel mailbox, or hosted email account. PhoneServer does not publish SMTP or IMAP ports itself and is not a public mail server.

## Before you start

Have these values from your mail provider:

- Email address used as the From address
- Login username, often the full email address
- Password or provider-generated app password
- SMTP host, port, and security
- IMAP host, port, and security
- Sent and Drafts folder names
- Optional display name shown to recipients

PhoneServer supports password or app-password authentication only. It does not support OAuth2, POP3, unencrypted SMTP/IMAP, or self-signed/untrusted certificates.

## Common provider settings

These are common defaults. Always use the values shown by your provider if they differ.

| Provider | SMTP | IMAP | Username | Notes |
|---|---|---|---|---|
| Gmail | `smtp.gmail.com`, port `587`, `starttls` | `imap.gmail.com`, port `993`, `tls` | Full email address | Enable IMAP and use a Google app password |
| Outlook / Hotmail / Microsoft 365 | `smtp.outlook.com`, port `587`, `starttls` | `imap.outlook.com`, port `993`, `tls` | Full email address | Use an app password where supported; OAuth2 is not supported |
| Yahoo Mail | `smtp.mail.yahoo.com`, port `587`, `starttls` | `imap.mail.yahoo.com`, port `993`, `tls` | Full email address | Generate a Yahoo app password |
| Zoho Mail | `smtp.zoho.com`, port `587`, `starttls` | `imap.zoho.com`, port `993`, `tls` | Full email address | Use the account password or app password required by Zoho |
| VPS / cPanel / Dovecot / Postfix | SMTP submission host, port `587` with `starttls` or `465` with `tls` | IMAP host, port `993`, `tls` | Mailbox login required by the server | Use a valid public CA certificate matching the hostname |

Avoid legacy ports `25` and `143` for the app. Port `25` is often blocked by mobile carriers and ISPs, and port `143` is usually unencrypted.

## Prepare a VPS mailbox

If you run mail on your own VPS:

1. Point the domain's MX record to the mail hostname.
2. Create an A record for the mail hostname and reverse DNS (PTR) for the server IP.
3. Create the mailbox and set its password or app password.
4. Enable IMAP and authenticated SMTP submission.
5. Open only the ports you need, typically `587`, `465`, and `993`.
6. Install a public CA certificate whose name matches the SMTP and IMAP hostnames you enter in PhoneServer.
7. Configure SPF, DKIM, and DMARC for delivery reliability; PhoneServer does not manage DNS records.

PhoneServer verifies certificates using the platform trust store and hostname verification. A self-signed certificate will not be accepted, and certificate validation is never disabled.

## Add the account in the app

1. Open PhoneServer and select **Mail** from the navigation drawer.
2. Tap **Accounts**.
3. Tap **New account**.
4. Fill in the form:
   - **Account name** — any label, such as `VPS mail`
   - **Email address** — the address messages are sent from
   - **Login username** — usually the full email address
   - **Password / app password** — the SMTP/IMAP login secret
   - **Display name** — optional recipient-facing name
   - **SMTP host and port** — for example `smtp.example.com`, `587`, `starttls`
   - **IMAP host and port** — for example `imap.example.com`, `993`, `tls`
   - **Sent folder** — usually `Sent`
   - **Drafts folder** — usually `Drafts`
5. Tap **Save**.

Saving performs a real SMTP login and a real IMAP login before the account is persisted. If verification fails, fix the reported setting and save again. The password field is cleared after save; leave it blank when editing to keep the stored password.

To recheck a saved account, tap its row in the account list, then tap **Test**. Tap **Delete** to remove the account, its stored password, and its cached messages.

## Send and receive mail

After the account is saved:

1. Return to **Mail** and select the account.
2. Tap **Sync** to download recent `INBOX` messages into the local cache.
3. Tap **Search** to filter the cache by subject, sender, or message text.
4. Tap **Compose** to write a message, add attachments, and tap **Send** or **Save draft**.

Sent messages are delivered through SMTP and cached locally. PhoneServer also tries to append a copy to the configured IMAP Sent folder; if that append fails, the SMTP delivery has still succeeded. Drafts are saved locally on the device and are not uploaded to IMAP.

## Add an account through the API

An API token with `mail.write` permission can create an account. The save performs the same SMTP and IMAP verification as the app.

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "VPS mail",
    "email": "me@example.com",
    "username": "me@example.com",
    "password": "APP_PASSWORD",
    "smtpHost": "smtp.example.com",
    "smtpPort": 587,
    "smtpSecurity": "starttls",
    "imapHost": "imap.example.com",
    "imapPort": 993,
    "imapSecurity": "tls",
    "fromName": "PhoneServer",
    "sentFolder": "Sent",
    "draftsFolder": "Drafts"
  }' \
  http://<device-ip>:8080/api/v1/mail/accounts
```

Passwords are encrypted with the Android Keystore, never returned by mail API responses, and never written to logs. See [API.md](API.md#7-mail-api-apiv1mail) for the complete mail endpoint reference.

## Troubleshooting

| Symptom | What to check |
|---|---|
| SMTP connection failed | SMTP host, port, `starttls` vs `tls`, firewall rules, and mobile-carrier port blocking |
| IMAP connection failed | IMAP host, port, IMAP enabled for the mailbox, and firewall rules |
| Authentication failed | Username format, app password, normal-password settings, and account lockouts |
| Certificate or hostname error | The certificate must be valid, trusted by Android, and issued for the exact hostname entered |
| No messages after sync | Confirm `INBOX` exists, the mailbox has mail, and the account was synced |
| Sent copy missing on the server | SMTP delivery succeeded; the IMAP Sent append is best-effort and depends on the configured Sent folder |
| Password field is blank when editing | Expected behavior; leaving it blank keeps the encrypted saved password |

For provider-specific fields, use the settings shown by your mail provider rather than guessing hostnames or ports.

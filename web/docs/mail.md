# Mail client and SMTP/IMAP integration

PhoneServer's mail feature connects to an external mail provider, usually a VPS mail server or a hosted mailbox that you control. It does not publish SMTP or IMAP ports itself and must not be described as a public mail server.

For step-by-step account creation, provider settings, VPS setup, and troubleshooting, see [email-setup.md](email-setup.md).

## Supported settings

- SMTP host, port, and security (`tls` or `starttls`)
- IMAP host, port, and security (`tls` or `starttls`)
- Username and password/app password
- Display name, Sent folder, and Drafts folder
- Multiple accounts with independent settings and cached messages

Creating or updating an account verifies its settings with a real SMTP login and IMAP login before the account is saved. The test endpoint can recheck an existing account.

## Security model

- Passwords are encrypted with a per-account Android Keystore AES/GCM key and are never returned by an API.
- Mail account metadata and cached messages are stored in encrypted JSON.
- TLS uses the platform default trust store and hostname verification. Certificate validation is never disabled.
- HTML mail is sanitized before storage, API output, and display.
- Attachment names are sanitized, attachment bytes are size limited, and storage paths are validated against the app-private mail root.
- Network operations run off the main UI thread.

## Synchronization

`POST /mail/accounts/{id}/sync` opens an IMAP session, selects `INBOX`, fetches the newest UIDs, parses MIME into text/HTML and attachment parts, and writes a local cache. Search is performed over that cache. Opening a cached message can mark it read locally and on IMAP.

Downloaded message bodies are capped at 20 MB. Attachments are capped at 5 MB each and 10 MB total for outgoing messages.

## Sending

`POST /mail/accounts/{id}/send` builds a MIME message, submits it with SMTP, stores a local Sent copy, then attempts to append the same message to the configured IMAP Sent folder. If the IMAP append fails, the SMTP send still reports success because the message was already delivered.

## Local drafts

`POST /mail/accounts/{id}/drafts` saves a draft locally on the device. It does not upload a draft to IMAP.

## API reference

See [API.md](API.md#7-mail-api-apiv1mail) for the endpoint table and request bodies.

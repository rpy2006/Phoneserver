package com.yadaventerprise.phoneserver.mail;

import java.util.ArrayList;
import java.util.List;

public final class MailCompose {

    public static final class OutgoingAttachment {
        public final String name;
        public final String contentType;
        public final byte[] bytes;

        public OutgoingAttachment(String name, String contentType, byte[] bytes) {
            this.name = name;
            this.contentType = contentType;
            this.bytes = bytes == null ? new byte[0] : bytes;
        }
    }

    public String to = "";
    public String cc = "";
    public String bcc = "";
    public String subject = "";
    public String text = "";
    public String html = "";
    public String inReplyTo = "";
    public String references = "";
    public final List<OutgoingAttachment> attachments = new ArrayList<>();

    public void validate() throws MailException {
        String recipients = to + "," + cc + "," + bcc;
        boolean found = false;
        for (String address : recipients.split("[,;\\s]+")) {
            if (address.isEmpty()) continue;
            if (!MailValidation.isEmail(address)) throw new MailException("invalid recipient: " + address);
            found = true;
        }
        if (!found) throw new MailException("at least one recipient is required");
        if (subject.length() > 500) throw new MailException("subject is too long");
        if (text.length() > 2_000_000 || html.length() > 2_000_000) {
            throw new MailException("message body is too large");
        }
        long total = 0;
        for (OutgoingAttachment attachment : attachments) {
            total += attachment.bytes.length;
            if (attachment.bytes.length > 5_000_000) throw new MailException("an attachment is too large");
        }
        if (total > 10_000_000) throw new MailException("attachments are too large");
    }
}

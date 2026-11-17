package com.yadaventerprise.phoneserver.mail;

public final class MailException extends Exception {

    private final int status;

    public MailException(String message) {
        this(message, 400);
    }

    public MailException(String message, int status) {
        super(message == null ? "mail operation failed" : message);
        this.status = status;
    }

    public MailException(String message, Throwable cause) {
        super(message == null ? "mail operation failed" : message, cause);
        this.status = 502;
    }

    public int status() {
        return status;
    }
}

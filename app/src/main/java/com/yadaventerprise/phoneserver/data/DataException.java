package com.yadaventerprise.phoneserver.data;

/**
 * Checked exception for all storage/API layer failures. Messages are written to
 * be safe to return to a client (no secrets, no absolute host paths).
 */
public class DataException extends Exception {

    private final int httpStatus;

    public DataException(String message) {
        this(message, 400);
    }

    public DataException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public DataException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 400;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public static DataException notFound(String what) {
        return new DataException(what + " not found", 404);
    }

    public static DataException conflict(String message) {
        return new DataException(message, 409);
    }

    public static DataException tooLarge(String message) {
        return new DataException(message, 413);
    }

    public static DataException forbidden(String message) {
        return new DataException(message, 403);
    }
}

package com.yadaventerprise.phoneserver.data.remote;

public final class RemoteRole {
    public static final String OWNER = "owner";
    public static final String ADMINISTRATOR = "administrator";
    public static final String MANAGER = "manager";
    public static final String VIEWER = "viewer";
    public static final String DEVICE = "device";

    private RemoteRole() {
    }

    public static boolean isValid(String value) {
        return OWNER.equals(value) || ADMINISTRATOR.equals(value) || MANAGER.equals(value)
                || VIEWER.equals(value);
    }

    public static String normalize(String value) {
        return isValid(value) ? value : VIEWER;
    }
}

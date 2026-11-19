package com.yadaventerprise.phoneserver.data.remote;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class RemotePermissions {
    public static final String DASHBOARD = "remote.dashboard";
    public static final String DEVICES_READ = "remote.devices.read";
    public static final String DEVICES_WRITE = "remote.devices.write";
    public static final String PAIRING = "remote.pairing.approve";
    public static final String USERS_READ = "remote.users.read";
    public static final String USERS_WRITE = "remote.users.write";
    public static final String ROLES_READ = "remote.roles.read";
    public static final String ROLES_WRITE = "remote.roles.write";
    public static final String FILES_READ = "remote.files.read";
    public static final String FILES_WRITE = "remote.files.write";
    public static final String SHARES_WRITE = "remote.shares.write";
    public static final String AUDIT_READ = "remote.audit.read";
    public static final String SETTINGS_READ = "remote.settings.read";
    public static final String SETTINGS_WRITE = "remote.settings.write";
    public static final String SERVER_CONTROL = "remote.server.control";

    public static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            DASHBOARD, DEVICES_READ, DEVICES_WRITE, PAIRING, USERS_READ, USERS_WRITE, ROLES_READ,
            ROLES_WRITE, FILES_READ, FILES_WRITE, SHARES_WRITE, AUDIT_READ, SETTINGS_READ,
            SETTINGS_WRITE, SERVER_CONTROL)));

    private static final Set<String> DEVICE_PERMISSIONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(DASHBOARD, DEVICES_READ, FILES_READ, FILES_WRITE)));

    private RemotePermissions() {
    }

    public static boolean isValid(String permission) {
        return permission != null && ALL.contains(permission);
    }

    public static Set<String> forRole(String role) {
        Set<String> result = new LinkedHashSet<>();
        if (RemoteRole.OWNER.equals(role) || RemoteRole.ADMINISTRATOR.equals(role)) {
            result.addAll(ALL);
            return result;
        }
        if (RemoteRole.MANAGER.equals(role)) {
            result.add(DASHBOARD);
            result.add(DEVICES_READ);
            result.add(FILES_READ);
            result.add(FILES_WRITE);
            result.add(SHARES_WRITE);
            return result;
        }
        if (RemoteRole.VIEWER.equals(role)) {
            result.add(DASHBOARD);
            result.add(DEVICES_READ);
            result.add(FILES_READ);
            return result;
        }
        if (RemoteRole.DEVICE.equals(role)) {
            result.addAll(DEVICE_PERMISSIONS);
        }
        return result;
    }
}

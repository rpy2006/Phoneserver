package com.yadaventerprise.phoneserver.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.util.List;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class RemoteModelTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        RemoteUserStore.reset();
        RemoteSessionStore.reset();
        RemoteDeviceStore.reset();
        RemotePairingStore.reset();
        RemoteAuditStore.reset();
        RemoteSettingsStore.reset();
        RemoteUserStore.get(context).clear();
        RemoteSessionStore.get(context).clear();
        RemoteDeviceStore.get(context).clear();
        RemotePairingStore.get(context).clear();
        RemoteAuditStore.get(context).clear();
        RemoteSettingsStore.get(context).clear();
    }

    @Test
    public void rolePermissionsFollowLeastPrivilege() {
        assertTrue(RemotePermissions.forRole(RemoteRole.OWNER).containsAll(RemotePermissions.ALL));
        assertTrue(RemotePermissions.forRole(RemoteRole.ADMINISTRATOR).containsAll(RemotePermissions.ALL));
        assertTrue(RemotePermissions.forRole(RemoteRole.MANAGER).contains(RemotePermissions.FILES_WRITE));
        assertFalse(RemotePermissions.forRole(RemoteRole.MANAGER).contains(RemotePermissions.USERS_WRITE));
        assertTrue(RemotePermissions.forRole(RemoteRole.VIEWER).contains(RemotePermissions.DASHBOARD));
        assertFalse(RemotePermissions.forRole(RemoteRole.VIEWER).contains(RemotePermissions.FILES_WRITE));
        assertTrue(RemotePermissions.forRole(RemoteRole.DEVICE).contains(RemotePermissions.DEVICES_READ));
        assertFalse(RemotePermissions.forRole(RemoteRole.DEVICE).contains(RemotePermissions.USERS_READ));
        assertFalse(RemotePermissions.isValid("remote.everything"));
    }

    @Test
    public void userPermissionOverridesAndOwnerProtectionWork() {
        RemoteUser user = new RemoteUser();
        user.role = RemoteRole.VIEWER;
        user.grantedPermissions.add(RemotePermissions.FILES_WRITE);
        assertTrue(user.hasPermission(RemotePermissions.FILES_WRITE));
        user.deniedPermissions.add(RemotePermissions.FILES_WRITE);
        assertFalse(user.hasPermission(RemotePermissions.FILES_WRITE));
        user.role = RemoteRole.OWNER;
        assertTrue(user.hasPermission(RemotePermissions.SERVER_CONTROL));
        assertTrue(user.hasPermission(RemotePermissions.FILES_WRITE));
    }

    @Test
    public void usernamesAreNormalisedAndValidated() {
        assertEquals("admin.one", RemoteUser.normalizeUsername(" Admin.One "));
        assertTrue(RemoteUser.isValidUsername("user_1"));
        assertTrue(RemoteUser.isValidUsername("a.b-c"));
        assertFalse(RemoteUser.isValidUsername("ab"));
        assertFalse(RemoteUser.isValidUsername("bad name"));
        assertFalse(RemoteUser.isValidUsername("bad@name"));
    }

    @Test
    public void settingsDefaultsAreSecureAndClampInvalidValues() {
        RemoteSettings defaults = new RemoteSettings();
        assertFalse(defaults.enabled);
        assertTrue(defaults.requireHttps);
        assertFalse(defaults.allowRemoteFiles);
        assertFalse(defaults.allowServerControl);

        JSONObject patch = new JSONObject();
        try {
            patch.put("sessionTtlMinutes", 99999);
            patch.put("offlineThresholdSeconds", -4);
            patch.put("allowRemoteFiles", true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        defaults.apply(patch);
        assertEquals(720, defaults.sessionTtlMinutes);
        assertEquals(180, defaults.offlineThresholdSeconds);
        assertTrue(defaults.allowRemoteFiles);
    }

    @Test
    public void userStorePersistsHashesNotPlaintextPasswords() {
        RemoteUser draft = new RemoteUser();
        draft.username = "admin";
        draft.displayName = "Administrator";
        draft.role = RemoteRole.ADMINISTRATOR;
        RemoteUser created = RemoteUserStore.get(context).create(draft, "correct horse battery staple");
        assertNotNull(created);
        assertFalse(created.passwordHash.contains("correct horse"));
        assertTrue(created.verifyPassword("correct horse battery staple"));
        assertFalse(created.verifyPassword("wrong password"));

        RemoteUserStore.reset();
        RemoteUser loaded = RemoteUserStore.get(context).findByUsername("admin");
        assertNotNull(loaded);
        assertTrue(loaded.verifyPassword("correct horse battery staple"));
    }

    @Test
    public void sessionsAreVerifiedByHashAndCanBeRevoked() {
        RemoteSessionStore.Created created = RemoteSessionStore.get(context).create(
                RemoteSession.SUBJECT_USER, "user-1", 60_000L, "10.0.0.8", null, null);
        assertNotNull(created);
        assertFalse(created.session.tokenHash.equals(created.token));
        assertNotNull(RemoteSessionStore.get(context).verify(created.token));
        RemoteSessionStore.get(context).revoke(created.session.id);
        assertNull(RemoteSessionStore.get(context).verify(created.token));
    }

    @Test
    public void pairingCodesAreHashedAndTokensAreOneTime() {
        RemotePairingStore.Created created = RemotePairingStore.get(context).create("Tablet", 600_000L);
        assertNotNull(created);
        assertFalse(created.request.codeHash.contains(created.code));
        for (int i = 0; i < 12; i++) {
            assertNotNull(RemotePairingStore.get(context).findByCode(created.code));
        }
        RemotePairingRequest found = RemotePairingStore.get(context).findByCode(created.code);
        assertNotNull(found);
        RemotePairingRequest submitted = RemotePairingStore.get(context).submitDeviceInfo(
                found.id, "Tablet", new JSONObject());
        assertNotNull(submitted);
        String deviceId = "device-1";
        assertNotNull(RemotePairingStore.get(context).approve(found.id, "owner", deviceId));
        assertTrue(RemotePairingStore.get(context).consume(found.id, "hash-1"));
        assertFalse(RemotePairingStore.get(context).consume(found.id, "hash-2"));
    }

    @Test
    public void auditDetailsAreSanitised() {
        RemoteAuditStore store = RemoteAuditStore.get(context);
        store.append(RemoteAuditEntry.AUTH_FAILED, "user", "user", "10.0.0.9",
                "token=abc123 Authorization: Bearer super-secret", false);
        List<RemoteAuditEntry> entries = store.all(10);
        assertEquals(1, entries.size());
        assertFalse(entries.get(0).details.contains("abc123"));
        assertFalse(entries.get(0).details.contains("super-secret"));
        assertTrue(entries.get(0).details.contains("[redacted]"));
    }
}

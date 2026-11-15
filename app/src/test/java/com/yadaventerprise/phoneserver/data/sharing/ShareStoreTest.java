package com.yadaventerprise.phoneserver.data.sharing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ShareStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ShareStore store;

    @Before
    public void setUp() throws Exception {
        ShareSessionStore.reset();
        store = new ShareStore(new File(tmp.newFolder("shares"), "links.json"));
    }

    @Test
    public void createReturnsRawTokenOnceAndPersistsOnlyItsHash() {
        ShareLink draft = draft("Photos", "Pictures", ShareLink.TYPE_FOLDER);
        ShareStore.Created created = store.create(draft, "secret");

        assertNotNull(created);
        assertNotNull(created.token);
        assertTrue(created.token.startsWith(ShareToken.PREFIX));
        assertEquals(64, created.link.tokenHash.length());
        assertTrue(created.link.isPasswordProtected());
        assertNotNull(store.findByTokenHash(ShareToken.hash(created.token)));
        assertNull(store.findByTokenHash(created.token));
        assertTrue(SharePassword.verify("secret", store.get(created.link.id).passwordHash));
    }

    @Test
    public void downloadConsumptionIsAtomicAndEnforcesLimit() {
        ShareLink draft = draft("Report", "Documents/report.pdf", ShareLink.TYPE_FILE);
        draft.maxDownloads = 2;
        ShareStore.Created created = store.create(draft, null);
        String hash = ShareToken.hash(created.token);

        ShareStore.ConsumeResult first = store.consumeDownload(hash, 1000);
        assertTrue(first.ok());
        assertEquals(1, first.link.downloadCount);
        ShareStore.ConsumeResult second = store.consumeDownload(hash, 2000);
        assertTrue(second.ok());
        assertEquals(2, second.link.downloadCount);
        ShareStore.ConsumeResult third = store.consumeDownload(hash, 3000);
        assertFalse(third.ok());
        assertEquals(ShareStore.ERROR_LIMIT, third.error);
        assertEquals(2, store.get(created.link.id).downloadCount);
    }

    @Test
    public void expiredAndRevokedLinksCannotBeConsumed() {
        ShareStore.Created created = store.create(draft("Old", "old.txt", ShareLink.TYPE_FILE), null);
        String id = created.link.id;
        String hash = ShareToken.hash(created.token);

        ShareLink update = store.get(id);
        update.expiresAt = 500;
        store.update(id, update, null, false);
        assertEquals(ShareStore.ERROR_EXPIRED, store.consumeDownload(hash, 600).error);

        store.setRevoked(id, true);
        assertEquals(ShareStore.ERROR_REVOKED, store.consumeDownload(hash, 100).error);
        assertEquals(ShareLink.STATUS_REVOKED, store.get(id).status(100));
    }

    @Test
    public void rotationReplacesTokenAndResetsDownloads() {
        ShareStore.Created created = store.create(draft("File", "file.txt", ShareLink.TYPE_FILE), null);
        store.consumeDownload(ShareToken.hash(created.token), 1000);
        assertEquals(1, store.get(created.link.id).downloadCount);

        ShareStore.Created rotated = store.rotateToken(created.link.id);
        assertNotNull(rotated);
        assertFalse(created.token.equals(rotated.token));
        assertEquals(0, rotated.link.downloadCount);
        assertNull(store.findByTokenHash(ShareToken.hash(created.token)));
        assertNotNull(store.findByTokenHash(ShareToken.hash(rotated.token)));
    }

    @Test
    public void updateKeepsSecretMaterialAndCanClearPassword() {
        ShareStore.Created created = store.create(draft("File", "file.txt", ShareLink.TYPE_FILE), "secret");
        String tokenHash = created.link.tokenHash;

        ShareLink changes = store.get(created.link.id);
        changes.name = "Renamed";
        changes.permission = ShareLink.PERMISSION_VIEW;
        ShareLink updated = store.update(created.link.id, changes, null, false);
        assertEquals("Renamed", updated.name);
        assertEquals(ShareLink.PERMISSION_VIEW, updated.permission);
        assertEquals(tokenHash, updated.tokenHash);
        assertTrue(updated.isPasswordProtected());

        ShareLink cleared = store.update(created.link.id, updated, null, true);
        assertFalse(cleared.isPasswordProtected());
        assertEquals(tokenHash, cleared.tokenHash);
    }

    @Test
    public void secretRotationAndPasswordChangeRevokeUnlockedSessions() {
        ShareStore.Created created = store.create(draft("File", "file.txt", ShareLink.TYPE_FILE), "secret");
        String session = ShareSessionStore.create(created.link.id);
        assertTrue(ShareSessionStore.isValid(session, created.link.id));

        store.rotateToken(created.link.id);
        assertFalse(ShareSessionStore.isValid(session, created.link.id));

        String second = ShareSessionStore.create(created.link.id);
        assertTrue(ShareSessionStore.isValid(second, created.link.id));
        ShareLink changes = store.get(created.link.id);
        store.update(created.link.id, changes, "new-secret", false);
        assertFalse(ShareSessionStore.isValid(second, created.link.id));
    }

    private ShareLink draft(String name, String path, String type) {
        ShareLink link = new ShareLink();
        link.name = name;
        link.rootPath = path;
        link.type = type;
        link.permission = ShareLink.PERMISSION_DOWNLOAD;
        return link;
    }
}

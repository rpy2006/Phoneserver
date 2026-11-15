package com.yadaventerprise.phoneserver.data.sharing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShareLinkTest {

    @Test
    public void statusReflectsExpiryAndRevocation() {
        ShareLink link = new ShareLink();
        link.expiresAt = 2_000;
        assertTrue(link.isActive(1_000));
        assertEquals(ShareLink.STATUS_ACTIVE, link.status(1_000));
        assertEquals(ShareLink.STATUS_EXPIRED, link.status(2_000));
        assertFalse(link.isActive(2_000));

        link.revoked = true;
        assertEquals(ShareLink.STATUS_REVOKED, link.status(1_000));
        assertFalse(link.isActive(1_000));

        link.revoked = false;
        link.expiresAt = 0;
        assertTrue(link.isActive(Long.MAX_VALUE));
    }

    @Test
    public void downloadLimitsAreReported() {
        ShareLink link = new ShareLink();
        assertFalse(link.isDownloadLimitReached());
        assertEquals(-1, link.remainingDownloads());

        link.maxDownloads = 2;
        link.downloadCount = 1;
        assertFalse(link.isDownloadLimitReached());
        assertEquals(1, link.remainingDownloads());

        link.downloadCount = 2;
        assertTrue(link.isDownloadLimitReached());
        assertEquals(0, link.remainingDownloads());
    }

    @Test
    public void passwordProtectionRequiresHash() {
        ShareLink link = new ShareLink();
        assertFalse(link.isPasswordProtected());
        link.passwordHash = SharePassword.hash("secret");
        assertTrue(link.isPasswordProtected());
    }
}

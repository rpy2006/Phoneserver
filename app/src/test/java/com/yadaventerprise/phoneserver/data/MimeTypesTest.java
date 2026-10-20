package com.yadaventerprise.phoneserver.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MimeTypesTest {

    @Test
    public void guess_isCaseInsensitiveAndDefaultsToBinary() {
        assertEquals("image/png", MimeTypes.guess("Photo.PNG"));
        assertEquals("application/pdf", MimeTypes.guess("report.pdf"));
        assertEquals("text/html", MimeTypes.guess("index.html"));
        assertEquals("application/octet-stream", MimeTypes.guess("data.bin"));
        assertEquals("application/octet-stream", MimeTypes.guess("noextension"));
        assertEquals("application/octet-stream", MimeTypes.guess(null));
    }

    @Test
    public void category_groupsBroadTypes() {
        assertEquals("images", MimeTypes.category("image/png"));
        assertEquals("videos", MimeTypes.category("video/mp4"));
        assertEquals("audio", MimeTypes.category("audio/mpeg"));
        assertEquals("documents", MimeTypes.category("application/pdf"));
        assertEquals("documents", MimeTypes.category("text/plain"));
        assertEquals("other", MimeTypes.category("application/zip"));
        assertEquals("other", MimeTypes.category(null));
    }

    @Test
    public void isInlineSafe_neverAllowsActiveContent() {
        assertTrue(MimeTypes.isInlineSafe("image/png"));
        assertTrue(MimeTypes.isInlineSafe("application/pdf"));
        assertTrue(MimeTypes.isInlineSafe("video/mp4"));
        assertFalse("HTML must never render inline", MimeTypes.isInlineSafe("text/html"));
        assertFalse("SVG can carry script", MimeTypes.isInlineSafe("image/svg+xml"));
        assertFalse(MimeTypes.isInlineSafe("application/javascript"));
        assertFalse(MimeTypes.isInlineSafe(null));
    }
}

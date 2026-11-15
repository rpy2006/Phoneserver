package com.yadaventerprise.phoneserver.data.sharing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShareTokenTest {

    @Test
    public void generatedTokensAreRandomAndHashable() {
        String token = ShareToken.generate();
        assertTrue(token.startsWith(ShareToken.PREFIX));
        assertEquals(ShareToken.PREFIX.length() + 64, token.length());
        assertNotEquals(token, ShareToken.generate());
        String hash = ShareToken.hash(token);
        assertEquals(64, hash.length());
        assertTrue(ShareToken.matches(token, hash));
        assertFalse(ShareToken.matches(token + "x", hash));
        assertFalse(ShareToken.matches("", hash));
    }
}

package com.yadaventerprise.phoneserver.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TokenUtilTest {

    @Test
    public void generateToken_hasRecognisablePrefixAndEntropy() {
        String token = TokenUtil.generateToken();
        assertTrue(token.startsWith(TokenUtil.PREFIX));
        // 32 random bytes -> 64 hex characters, plus the prefix.
        assertEquals(TokenUtil.PREFIX.length() + 64, token.length());
        assertNotEquals(token, TokenUtil.generateToken());
    }

    @Test
    public void hash_isDeterministicAndHexEncoded() {
        String token = TokenUtil.generateToken();
        String hash = TokenUtil.hash(token);
        assertEquals(hash, TokenUtil.hash(token));
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertNotEquals(token, hash);
    }

    @Test
    public void constantTimeEquals_comparesSafely() {
        assertTrue(TokenUtil.constantTimeEquals("abc", "abc"));
        assertFalse(TokenUtil.constantTimeEquals("abc", "abd"));
        assertFalse(TokenUtil.constantTimeEquals(null, "abc"));
        assertFalse(TokenUtil.constantTimeEquals("abc", null));
    }
}

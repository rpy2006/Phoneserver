package com.yadaventerprise.phoneserver.data.sharing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SharePasswordTest {

    @Test
    public void hashesAreSaltedAndVerifiable() {
        String first = SharePassword.hash("correct horse battery staple");
        String second = SharePassword.hash("correct horse battery staple");
        assertTrue(SharePassword.isHashed(first));
        assertNotEquals(first, second);
        assertTrue(SharePassword.verify("correct horse battery staple", first));
        assertTrue(SharePassword.verify("correct horse battery staple", second));
        assertFalse(SharePassword.verify("wrong", first));
    }

    @Test
    public void malformedHashesAreRejected() {
        assertFalse(SharePassword.verify("anything", ""));
        assertFalse(SharePassword.verify("anything", "plaintext"));
        assertFalse(SharePassword.verify("anything", "pbkdf2-sha256$1$aa$bb"));
        assertFalse(SharePassword.verify(null, SharePassword.hash("secret")));
    }
}

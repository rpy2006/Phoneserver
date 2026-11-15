package com.yadaventerprise.phoneserver.data.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TokenPolicyTest {

    @Test
    public void viewerCanReadButNotWrite() {
        TokenPolicy policy = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        assertTrue(policy.can(TokenPolicy.PROJECTS_READ));
        assertTrue(policy.can(TokenPolicy.FILES_READ));
        assertFalse(policy.can(TokenPolicy.FILES_WRITE));
        assertFalse(policy.canWrite());
    }

    @Test
    public void editorCanWriteScopedResources() {
        TokenPolicy policy = TokenPolicy.preset(TokenPolicy.ROLE_EDITOR);
        assertTrue(policy.can(TokenPolicy.PROJECTS_WRITE));
        assertTrue(policy.can(TokenPolicy.BACKUPS_WRITE));
        assertFalse(policy.can(TokenPolicy.TOKENS_MANAGE));
        assertTrue(policy.canWrite());
    }

    @Test
    public void adminCanManageTokens() {
        TokenPolicy policy = TokenPolicy.preset(TokenPolicy.ROLE_ADMIN);
        assertTrue(policy.isAdmin());
        assertTrue(policy.can(TokenPolicy.TOKENS_MANAGE));
        assertTrue(policy.can(TokenPolicy.SYSTEM_READ));
        assertTrue(policy.canWrite());
    }

    @Test
    public void expiryIsEvaluatedAgainstCurrentTime() {
        TokenPolicy policy = TokenPolicy.preset(TokenPolicy.ROLE_VIEWER);
        assertFalse(policy.isExpired());
        policy.expiresAt = System.currentTimeMillis() - 1;
        assertTrue(policy.isExpired());
    }

    @Test
    public void invalidRoleFallsBackToViewer() {
        TokenPolicy policy = TokenPolicy.preset("owner");
        assertEquals(TokenPolicy.ROLE_VIEWER, policy.role);
        assertFalse(TokenPolicy.isValidRole("owner"));
    }
}

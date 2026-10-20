package com.yadaventerprise.phoneserver.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TokenStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context context;
    private String projectId;

    @Before
    public void setUp() throws Exception {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
        projectId = ProjectStore.create(context, "Alpha", "", 0, 0).id;
    }

    @Test
    public void createdTokenIsReturnedOnceAndVerifies() throws Exception {
        TokenStore.Created created = TokenStore.create(context, "web-app", null, true);

        assertNotNull(created.token);
        assertTrue(created.token.startsWith(TokenUtil.PREFIX));
        assertNotNull(created.record);
        assertEquals("web-app", created.record.name);
        assertNull(created.record.projectId);

        Records.ApiTokenRecord verified = TokenStore.verify(context, created.token);
        assertNotNull(verified);
        assertEquals(created.record.id, verified.id);
        assertTrue(verified.canWrite);
        assertFalse(verified.revoked);
    }

    @Test
    public void unknownAndRevokedTokensAreRejected() throws Exception {
        TokenStore.Created created = TokenStore.create(context, "web-app", null, true);
        assertNull(TokenStore.verify(context, "pst_totally-bogus"));
        assertNull(TokenStore.verify(context, ""));
        assertNull(TokenStore.verify(context, null));

        TokenStore.revoke(context, created.record.id);
        assertNull("revoked token must not authenticate",
                TokenStore.verify(context, created.token));
    }

    @Test
    public void tokenCanBeScopedToASingleProject() throws Exception {
        String other = ProjectStore.create(context, "Beta", "", 0, 0).id;

        TokenStore.Created scoped = TokenStore.create(context, "project-token", projectId, false);
        assertEquals(projectId, scoped.record.projectId);
        assertFalse(scoped.record.canWrite);

        Records.ApiTokenRecord verified = TokenStore.verify(context, scoped.token);
        assertNotNull(verified);
        assertEquals(projectId, verified.projectId);

        // A token scoped to Alpha does not authorise Beta.
        assertFalse(projectId.equals(other));
        assertThrows(DataException.class,
                () -> TokenStore.create(context, "bad", "nonexistent-project", true));
    }

    @Test
    public void listingSeparatesGlobalAndProjectTokens() throws Exception {
        String other = ProjectStore.create(context, "Beta", "", 0, 0).id;
        TokenStore.create(context, "global", null, true);
        TokenStore.create(context, "for-alpha", projectId, true);

        List<Records.ApiTokenRecord> all = TokenStore.list(context);
        assertEquals(2, all.size());

        List<Records.ApiTokenRecord> forProject = TokenStore.listForProject(context, projectId);
        assertEquals(2, forProject.size()); // global + alpha

        List<Records.ApiTokenRecord> forOther = TokenStore.listForProject(context, other);
        assertEquals(1, forOther.size()); // only the global one
        assertEquals("global", forOther.get(0).name);
    }

    @Test
    public void tokenNameIsValidated() {
        DataException empty = assertThrows(DataException.class,
                () -> TokenStore.create(context, "   ", null, true));
        assertEquals(400, empty.httpStatus());

        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 61; i++) longName.append('n');
        DataException tooLong = assertThrows(DataException.class,
                () -> TokenStore.create(context, longName.toString(), null, true));
        assertEquals(400, tooLong.httpStatus());
    }
}

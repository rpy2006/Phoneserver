package com.yadaventerprise.phoneserver.data.tokens;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.storage.ProjectStore;
import com.yadaventerprise.phoneserver.network.http.DataException;
import com.yadaventerprise.phoneserver.security.TokenUtil;
import com.yadaventerprise.phoneserver.testing.DataTestSupport;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

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

    @Test
    public void expiredTokenIsRejected() throws Exception {
        TokenStore.Created created = TokenStore.create(context, "expiring", null, true, System.currentTimeMillis() - 1000);
        assertNull(TokenStore.verify(context, created.token));
    }

    @Test
    public void renamePreservesPermissionsAndRegenerateInvalidatesOldKey() throws Exception {
        TokenStore.Created created = TokenStore.create(context, "old", projectId, false, 0, 10, 20, 2);
        TokenStore.rename(context, created.record.id, "renamed");
        Records.ApiTokenRecord renamed = TokenStore.findById(context, created.record.id);
        assertEquals("renamed", renamed.name);
        assertEquals(projectId, renamed.projectId);
        assertFalse(renamed.canWrite);
        assertEquals(10, renamed.dailyQuota);
        assertEquals(20, renamed.monthlyQuota);
        assertEquals(2, renamed.concurrentLimit);

        TokenStore.Created regenerated = TokenStore.regenerate(context, created.record.id);
        assertNotNull(regenerated.token);
        assertFalse(regenerated.token.equals(created.token));
        assertNull(TokenStore.verify(context, created.token));
        Records.ApiTokenRecord verified = TokenStore.verify(context, regenerated.token);
        assertNotNull(verified);
        assertEquals(created.record.id, verified.id);
        assertEquals("renamed", verified.name);
        assertFalse(verified.canWrite);
    }

    @Test
    public void quotaUpdatesPersist() throws Exception {
        TokenStore.Created created = TokenStore.create(context, "quota", null, true);
        TokenStore.updateQuotas(context, created.record.id, 11, 22, 3);
        Records.ApiTokenRecord updated = TokenStore.findById(context, created.record.id);
        assertEquals(11, updated.dailyQuota);
        assertEquals(22, updated.monthlyQuota);
        assertEquals(3, updated.concurrentLimit);
    }
}

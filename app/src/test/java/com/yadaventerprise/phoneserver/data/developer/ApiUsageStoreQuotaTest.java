package com.yadaventerprise.phoneserver.data.developer;

import static org.junit.Assert.*;

import android.content.Context;
import com.yadaventerprise.phoneserver.testing.DataTestSupport;
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
public class ApiUsageStoreQuotaTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Context context;

    @Before public void setUp() {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        DataTestSupport.freshDataRoot(context, tmp.getRoot());
        ApiUsageStore.reset();
    }

    @Test public void staleReservationIsCleanedUp() {
        ApiUsageStore store = ApiUsageStore.get(context);
        long now = 1_700_000_000_000L;
        assertTrue(store.tryConsumeQuota("tok1", now - 10 * 60 * 1000L, 10, 100));
        int removed = store.cleanupStaleReservations(now);
        assertEquals(1, removed);
        assertTrue(store.tryConsumeQuota("tok1", now, 1, 100));
    }

    @Test public void quotaReservationReplacedByActualUsage() {
        ApiUsageStore store = ApiUsageStore.get(context);
        long now = 1_700_000_000_000L;
        assertTrue(store.tryConsumeQuota("tok2", now, 1, 100));
        ApiUsageEntry actual = new ApiUsageEntry();
        actual.time = now; actual.tokenId = "tok2"; actual.method = "GET"; actual.path = "/api/v1/projects"; actual.status = 200;
        store.replaceReservation("tok2", actual);
        assertFalse(store.tryConsumeQuota("tok2", now, 1, 100));
    }

    @Test public void staleReservationIsIgnoredAfterRestartLoad() {
        ApiUsageStore store = ApiUsageStore.get(context);
        long now = System.currentTimeMillis();
        assertTrue(store.tryConsumeQuota("tok3", now - 10 * 60 * 1000L, 10, 100));
        ApiUsageStore.reset();
        ApiUsageStore store2 = ApiUsageStore.get(context);
        assertTrue(store2.tryConsumeQuota("tok3", now, 1, 100));
    }
}

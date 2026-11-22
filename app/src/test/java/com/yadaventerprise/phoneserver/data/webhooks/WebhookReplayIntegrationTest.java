package com.yadaventerprise.phoneserver.data.webhooks;

import static org.junit.Assert.*;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import com.yadaventerprise.phoneserver.testing.DataTestSupport;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class WebhookReplayIntegrationTest {
    private Context context;

    @Before
    public void setUp() {
        DataTestSupport.assumeRobolectricRuntimeSupported();
        context = RuntimeEnvironment.getApplication();
        WebhookReplayStore.reset();
    }

    @Test
    public void replayRejectedAcrossStoreReset() {
        long now = 1_700_000_000_000L;
        assertTrue(WebhookReplayStore.get(context).checkAndRecord("d1", now, now));
        WebhookReplayStore.reset();
        assertFalse(WebhookReplayStore.get(context).checkAndRecord("d1", now, now));
    }

    @Test
    public void oldTimestampRejected() {
        long now = 1_700_000_000_000L;
        assertFalse(WebhookDispatcher.verifyIncoming(context, "secret", String.valueOf(now - 10 * 60 * 1000L), "d2", "{}", "sha256=bad", now));
    }

    @Test
    public void invalidSignatureRejected() {
        long now = 1_700_000_000_000L;
        assertFalse(WebhookDispatcher.verifyIncoming(context, "secret", String.valueOf(now), "d3", "{\"a\":1}", "sha256=deadbeef", now));
    }

    @Test
    public void concurrentDuplicateDeliveriesOnlyOneAccepted() throws Exception {
        long now = 1_700_000_000_000L;
        String ts = String.valueOf(now);
        String body = "{\"ok\":true}";
        String sig = "sha256=" + sign(ts + ".dup." + body, "secret");
        final boolean[] results = new boolean[2];
        Thread t1 = new Thread(() -> results[0] = WebhookDispatcher.verifyIncoming(context, "secret", ts, "dup", body, sig, now));
        Thread t2 = new Thread(() -> results[1] = WebhookDispatcher.verifyIncoming(context, "secret", ts, "dup", body, sig, now));
        t1.start(); t2.start(); t1.join(); t2.join();
        assertTrue(results[0] ^ results[1]);
    }

    private static String sign(String payload, String secret) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}

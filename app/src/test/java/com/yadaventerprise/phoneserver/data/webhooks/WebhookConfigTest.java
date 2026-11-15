package com.yadaventerprise.phoneserver.data.webhooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class WebhookConfigTest {

    @Test
    public void roundTripsAndMatchesEvents() throws Exception {
        WebhookConfig config = new WebhookConfig();
        config.id = "hook-1";
        config.name = "Automation";
        config.url = "https://example.com/hook";
        config.secret = "secret";
        config.events.add(WebhookDispatcher.EVENT_FILE_UPLOADED);
        config.events.add(WebhookDispatcher.EVENT_AUTH_FAILURE);
        config.createdAt = 123;

        WebhookConfig copy = WebhookConfig.fromJson(new JSONObject(config.toJson().toString()));
        assertEquals(config.id, copy.id);
        assertEquals(config.url, copy.url);
        assertEquals(2, copy.events.size());
        assertTrue(copy.matches(WebhookDispatcher.EVENT_FILE_UPLOADED));
        assertFalse(copy.matches(WebhookDispatcher.EVENT_BACKUP_COMPLETED));
        copy.enabled = false;
        assertFalse(copy.matches(WebhookDispatcher.EVENT_FILE_UPLOADED));
    }

    @Test
    public void wildcardMatchesEverything() {
        WebhookConfig config = new WebhookConfig();
        config.events.add("*");
        assertTrue(config.matches("anything"));
    }
}

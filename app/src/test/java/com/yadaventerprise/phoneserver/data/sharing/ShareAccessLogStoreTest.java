package com.yadaventerprise.phoneserver.data.sharing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ShareAccessLogStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ShareAccessLogStore log;

    @Before
    public void setUp() throws Exception {
        log = new ShareAccessLogStore(new File(tmp.newFolder("shares"), "access-log.jsonl"));
    }

    @Test
    public void recordsAndFiltersAccessEventsWithoutSecrets() {
        ShareLink link = new ShareLink();
        link.id = "link-1";
        link.name = "Photos";
        log.record(ShareAccessEvent.DOWNLOAD, link, 200, "download started",
                "192.168.1.8", "trip/photo.jpg", "Browser");
        log.record(ShareAccessEvent.DENIED_PASSWORD, link, 401, "incorrect password",
                "10.0.0.2", "", "Browser");
        log.record(ShareAccessEvent.VISIT, null, 404, "unknown share link",
                "10.0.0.3", "", "Scanner");

        List<ShareAccessEvent> downloads = log.read(query(ShareAccessEvent.DOWNLOAD, ""));
        assertEquals(1, downloads.size());
        assertEquals("Photos", downloads.get(0).linkName);
        assertEquals("192.168.1.8", downloads.get(0).ip);

        List<ShareAccessEvent> denied = log.read(query("", "incorrect"));
        assertEquals(1, denied.size());
        assertEquals(ShareAccessEvent.DENIED_PASSWORD, denied.get(0).event);
        assertTrue(denied.get(0).detail.contains("password"));
    }

    @Test
    public void linkFilterIsExact() {
        ShareLink first = new ShareLink();
        first.id = "one";
        first.name = "One";
        ShareLink second = new ShareLink();
        second.id = "two";
        second.name = "Two";
        log.record(ShareAccessEvent.VISIT, first, 200, "", "127.0.0.1", "", "");
        log.record(ShareAccessEvent.VISIT, second, 200, "", "127.0.0.1", "", "");

        ShareAccessLogStore.Query query = new ShareAccessLogStore.Query();
        query.linkId = "two";
        List<ShareAccessEvent> events = log.read(query);
        assertEquals(1, events.size());
        assertEquals("two", events.get(0).linkId);
    }

    private ShareAccessLogStore.Query query(String event, String search) {
        ShareAccessLogStore.Query query = new ShareAccessLogStore.Query();
        query.event = event;
        query.search = search;
        return query;
    }
}

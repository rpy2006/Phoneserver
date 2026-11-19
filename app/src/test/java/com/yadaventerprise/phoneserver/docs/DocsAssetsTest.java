package com.yadaventerprise.phoneserver.docs;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class DocsAssetsTest {

    @Test
    public void offlineDocsAreBundled() throws Exception {
        File docs = new File("src/main/assets/docs");
        assertTrue("offline docs directory is missing", docs.isDirectory());
        for (String name : new String[]{"index.html", "styles.css", "docs.js", "openapi.json", "changelog.md", "mail.md", "email-setup.md", "remote-access.md", "remote-api.md"}) {
            assertTrue(name + " is missing", new File(docs, name).isFile());
        }
        File canonical = new File("../docs");
        for (String name : new String[]{"README.md", "features/remote-access.md", "api/remote-api.md", "api/openapi.json"}) {
            assertTrue(name + " is missing from canonical docs", new File(canonical, name).isFile());
        }
    }

    @Test
    public void openApiDescribesImplementedMailRoutes() throws Exception {
        String spec = read("src/main/assets/docs/openapi.json");
        assertTrue(spec.contains("\"/mail/accounts\""));
        assertTrue(spec.contains("\"/mail/accounts/{id}/test\""));
        assertTrue(spec.contains("\"/mail/accounts/{id}/sync\""));
        assertTrue(spec.contains("\"/mail/accounts/{id}/send\""));
        assertTrue(spec.contains("\"/mail/messages/{id}/attachments/{index}\""));
        assertTrue(spec.contains("\"/system/monitor\""));
        assertTrue(spec.contains("\"/system/automation\""));
        assertTrue(spec.contains("\"/system/tasks/{id}/run\""));
        assertTrue(spec.contains("\"/system/webhooks/{id}/test\""));
        assertTrue(spec.contains("\"/system/maintenance/clean\""));
        assertTrue(spec.contains("bearerAuth"));
    }

    @Test
    public void openApiDescribesRemoteAccessRoutes() throws Exception {
        String[] paths = new String[]{
                "auth/login", "pairing/request", "pairing/status", "dashboard", "pairing",
                "pairing/{id}/approve", "devices", "devices/{id}/heartbeat", "users",
                "roles", "audit", "settings", "server/start", "files/list", "files/download",
                "files/upload", "files/delete", "files/rename", "files/mkdir"
        };
        for (String specPath : new String[]{"src/main/assets/docs/openapi.json", "../docs/api/openapi.json"}) {
            String spec = read(specPath);
            for (String suffix : paths) {
                assertTrue(specPath + " is missing /remote/" + suffix,
                        spec.contains("\"/remote/" + suffix + "\""));
                assertTrue(specPath + " is missing /api/v1/remote/" + suffix,
                        spec.contains("/api/v1/remote/" + suffix));
            }
            assertTrue(specPath + " is missing the bearer scheme", spec.contains("bearerAuth"));
        }
    }

    @Test
    public void docsIndexMentionsRemoteAccessManagement() throws Exception {
        String canonical = read("../docs/README.md");
        String bundled = read("src/main/assets/docs/README.md");
        String site = read("src/main/assets/docs/index.html");
        String explorerData = read("src/main/assets/docs/docs.js");
        String webData = read("../web/data/docs.js");
        assertTrue(canonical.toLowerCase().contains("remote access"));
        assertTrue(canonical.toLowerCase().contains("remote management"));
        assertTrue(bundled.toLowerCase().contains("remote access"));
        assertTrue(site.contains("Remote access and management"));
        assertTrue(explorerData.contains("remote-access.md"));
        assertTrue(explorerData.contains("remote-api.md"));
        assertTrue(webData.contains("remote-access.md"));
        assertTrue(webData.contains("remote-api.md"));
    }

    @Test
    public void docsSiteUsesActualBasePathAndExplorer() throws Exception {
        String html = read("src/main/assets/docs/index.html");
        String js = read("src/main/assets/docs/docs.js");
        assertTrue(html.contains("/api/v1"));
        assertTrue(html.contains("Email setup"));
        assertTrue(html.contains("email-setup.md"));
        assertTrue(html.contains("Try API"));
        assertTrue(js.contains("/mail/accounts/{id}/test"));
        assertTrue(js.contains("/system/maintenance/clean"));
        assertTrue(js.contains("window.PhoneServerDocs.tryApi"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}

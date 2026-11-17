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
        for (String name : new String[]{"index.html", "styles.css", "docs.js", "openapi.json", "changelog.md", "mail.md", "email-setup.md"}) {
            assertTrue(name + " is missing", new File(docs, name).isFile());
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
        assertTrue(spec.contains("bearerAuth"));
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
        assertTrue(js.contains("window.PhoneServerDocs.tryApi"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}

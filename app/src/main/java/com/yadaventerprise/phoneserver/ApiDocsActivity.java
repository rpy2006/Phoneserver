package com.yadaventerprise.phoneserver;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class ApiDocsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_api_docs);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());

        String ip = NetworkUtils.getLocalIpAddress();
        if (ip == null) ip = "<device-ip>";
        String base = "http://" + ip + ":" + ServerService.PORT + DataApi.BASE;

        TextView docs = findViewById(R.id.docs_text);
        docs.setText(buildDocs(base));
    }

    private String buildDocs(String base) {
        StringBuilder sb = new StringBuilder();
        sb.append("PHONESERVER DATA API\n");
        sb.append("====================\n\n");
        sb.append("Base URL\n");
        sb.append(base).append("\n\n");
        sb.append("The server must be running and the phone must be reachable on the same network.\n\n");

        sb.append("Authentication\n");
        sb.append("--------------\n");
        sb.append("Every endpoint except /health requires credentials.\n\n");
        sb.append("1) Bearer token (recommended for apps & scripts):\n");
        sb.append("   Authorization: Bearer <token>\n");
        sb.append("   Create a token under Database & Storage > API Tokens.\n");
        sb.append("   A token can be scoped to one project, and can be read-only or read/write.\n\n");
        sb.append("2) Access password (admin access):\n");
        sb.append("   X-Auth-Password: <password>\n");
        sb.append("   or HTTP Basic auth, or ?pw=<password>.\n\n");

        sb.append("Endpoints\n");
        sb.append("---------\n");
        sb.append("GET  /health\n");
        sb.append("GET  /system\n");
        sb.append("GET/POST  /projects\n");
        sb.append("GET/PATCH/DELETE  /projects/{id}\n");
        sb.append("GET  /projects/{id}/stats\n");
        sb.append("GET  /projects/{id}/usage\n");
        sb.append("GET/POST  /projects/{id}/databases\n");
        sb.append("GET/DELETE  /projects/{id}/databases/{db}\n");
        sb.append("GET  /projects/{id}/databases/{db}/tables\n");
        sb.append("GET  /projects/{id}/databases/{db}/tables/{table}\n");
        sb.append("POST  /projects/{id}/databases/{db}/query\n");
        sb.append("POST  /projects/{id}/databases/{db}/transaction\n");
        sb.append("GET  /projects/{id}/databases/{db}/export\n");
        sb.append("POST  /projects/{id}/databases/{db}/import\n");
        sb.append("GET/POST  /projects/{id}/files\n");
        sb.append("GET/PATCH/DELETE  /projects/{id}/files/{fileId}\n");
        sb.append("GET  /projects/{id}/files/{fileId}/download\n");
        sb.append("GET/POST/DELETE  /projects/{id}/folders\n");
        sb.append("GET/POST  /projects/{id}/backups\n");
        sb.append("POST  /projects/{id}/backups/{backupId}/restore\n");
        sb.append("POST  /projects/{id}/backups/{backupId}/validate\n");
        sb.append("GET  /projects/{id}/backups/{backupId}/download\n");
        sb.append("GET/POST/DELETE  /tokens\n\n");

        sb.append("Query example\n");
        sb.append("-------------\n");
        sb.append("POST ").append(base).append("/projects/{id}/databases/{db}/query\n");
        sb.append("Body: {\"sql\":\"SELECT * FROM users LIMIT 10\",\"limit\":10,\"offset\":0}\n");
        sb.append("Response: {isSelect, keyword, columns, rows, rowsAffected, truncated, durationMs}\n\n");

        sb.append("curl example\n");
        sb.append("------------\n");
        sb.append("curl -H \"Authorization: Bearer $TOKEN\" \\\n");
        sb.append("  \"").append(base).append("/projects\"\n\n");
        sb.append("curl -X POST -H \"Authorization: Bearer $TOKEN\" \\\n");
        sb.append("  -H \"Content-Type: application/json\" \\\n");
        sb.append("  -d '{\"sql\":\"SELECT * FROM users\"}' \\\n");
        sb.append("  \"").append(base).append("/projects/{id}/databases/{db}/query\"\n\n");

        sb.append("JavaScript fetch example\n");
        sb.append("------------------------\n");
        sb.append("const base = \"").append(base).append("\";\n");
        sb.append("const token = \"<your-token>\";\n\n");
        sb.append("const res = await fetch(base + \"/projects\", {\n");
        sb.append("  headers: { Authorization: \"Bearer \" + token }\n");
        sb.append("});\n");
        sb.append("const data = await res.json();\n\n");
        sb.append("const q = await fetch(base + \"/projects/\" + id + \"/databases/\" + db + \"/query\", {\n");
        sb.append("  method: \"POST\",\n");
        sb.append("  headers: {\n");
        sb.append("    Authorization: \"Bearer \" + token,\n");
        sb.append("    \"Content-Type\": \"application/json\"\n");
        sb.append("  },\n");
        sb.append("  body: JSON.stringify({ sql: \"SELECT * FROM users LIMIT 10\" })\n");
        sb.append("});\n");
        sb.append("const rows = (await q.json()).rows;\n");

        return sb.toString();
    }
}

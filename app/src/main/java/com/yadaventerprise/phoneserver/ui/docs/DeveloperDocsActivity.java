package com.yadaventerprise.phoneserver.ui.docs;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import com.yadaventerprise.phoneserver.mail.MailValidation;
import com.yadaventerprise.phoneserver.network.http.DataApi;
import com.yadaventerprise.phoneserver.service.ServerService;
import com.yadaventerprise.phoneserver.util.NetworkUtils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class DeveloperDocsActivity extends AppCompatActivity {

    private WebView webView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        webView.addJavascriptInterface(new DocsBridge(), "PhoneServerDocs");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/docs/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("PhoneServerDocs");
            webView.destroy();
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private final class DocsBridge {

        @JavascriptInterface
        public String baseUrl() {
            String ip = NetworkUtils.getLocalIpAddress();
            if (ip == null) ip = "DEVICE-IP";
            return "http://" + ip + ":" + ServerService.PORT + DataApi.BASE;
        }

        @JavascriptInterface
        public String permissions() {
            return TokenPolicy.allPermissions().toString();
        }

        @JavascriptInterface
        public void tryApi(String requestJson) {
            executor.execute(() -> {
                JSONObject result;
                try {
                    JSONObject request = new JSONObject(requestJson);
                    result = DocsHttp.request(
                            request.optString("method", "GET"),
                            request.optString("url", ""),
                            request.optJSONObject("headers"),
                            request.optString("body", ""));
                } catch (Exception e) {
                    result = new JSONObject();
                    try {
                        result.put("status", 0);
                        result.put("statusText", "Client error");
                        result.put("body", MailValidation.cleanError(e.getMessage()));
                    } catch (Exception ignored) {
                    }
                }
                final String script = "PhoneServerDocsCallback.onTryResult("
                        + JSONObject.quote(result.toString()) + ")";
                runOnUiThread(() -> webView.evaluateJavascript(script, null));
            });
        }
    }
}

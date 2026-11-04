package com.yadaventerprise.phoneserver;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Phone-side control panel for the Ad Network Manager: switch it on, see the dashboard address,
 * read the one-time setup code and set the public HTTPS address used in embed tags.
 * Everything else (campaigns, sites, reports) lives in the web dashboard.
 */
public class AdNetworkActivity extends AppCompatActivity {
    private TextView status;
    private EditText publicUrl;
    private Button toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Ad Network");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(8), 0, dp(12));
        root.addView(title, matchWrap());

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextIsSelectable(true);
        root.addView(status, matchWrap());

        toggle = button("", v -> {
            if (AdNetworkManager.isRunning()) {
                AdNetworkManager.setEnabled(this, false);
                AdNetworkManager.stop();
            } else {
                AdNetworkManager.setEnabled(this, true);
                String err = AdNetworkManager.start(this);
                if (err != null) status.setText(err);
            }
            refresh();
        });
        root.addView(toggle, matchWrap());

        root.addView(button("Open dashboard in browser", v -> startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + AdNetworkManager.PORT + "/")))), matchWrap());

        root.addView(button("New setup code", v -> {
            AdNetworkManager.newSetupCode(this);
            refresh();
        }), matchWrap());

        publicUrl = new EditText(this);
        publicUrl.setHint("Public address for embed tags, e.g. https://ads.example.com");
        publicUrl.setSingleLine(true);
        publicUrl.setText(AdDb.get(this).setting("public_base_url", ""));
        root.addView(publicUrl, matchWrap());

        root.addView(button("Save public address", v -> {
            String u = publicUrl.getText().toString().trim();
            if (!u.isEmpty() && (!AdSecurity.isHttpUrl(u) || u.endsWith("/"))) {
                status.setText("Enter an address like https://ads.example.com with no trailing slash.");
                return;
            }
            AdDb.get(this).setSetting("public_base_url", u);
            refresh();
        }), matchWrap());

        TextView note = new TextView(this);
        note.setTextSize(12);
        note.setPadding(0, dp(16), 0, 0);
        note.setText("Websites served over HTTPS can only load the ad script from an HTTPS address, and visitors must be able to "
                + "reach it. Put this phone behind an HTTPS reverse proxy or tunnel that you control, then enter that address above. "
                + "The ad server runs while the PhoneServer service is running.");
        root.addView(note, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        String ip = NetworkUtils.getLocalIpAddress();
        StringBuilder sb = new StringBuilder();
        sb.append(AdNetworkManager.isRunning() ? "Status: running" : "Status: stopped").append('\n');
        sb.append("Dashboard on this network: http://").append(ip != null ? ip : "PHONE-IP").append(':').append(AdNetworkManager.PORT).append("/\n");
        if (AdNetworkManager.isRunning() && AdNetworkManager.needsSetup(this)) {
            sb.append("\nFirst-time setup code: ").append(AdNetworkManager.setupCode(this))
                    .append("\nEnter it on the dashboard page to create the administrator account.");
        }
        status.setText(sb.toString());
        toggle.setText(AdNetworkManager.isRunning() ? "Stop ad network" : "Start ad network");
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}

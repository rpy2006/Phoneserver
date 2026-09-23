package com.yadaventerprise.phoneserver;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class QrFragment extends Fragment {

    private View rootView;
    private ImageView qrImage;
    private TextView qrCaption, qrAddressSmall, qrStatusText, qrAddressValue, qrSecurityValue;
    private View qrStatusDot;
    private View myQrContent, scanQrContent;
    private TextView tabMyQrLabel, tabScanQrLabel;
    private View tabMyQrUnderline, tabScanQrUnderline;
    private android.widget.LinearLayout recentScansContainer;
    private TextView recentScansEmpty;

    private String currentServerUrl = null;
    private Bitmap currentQrBitmap = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshMyQr();
            handler.postDelayed(this, 3000);
        }
    };

    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), this::handleScanResult);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_qr, container, false);
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        qrImage = view.findViewById(R.id.qr_image);
        qrCaption = view.findViewById(R.id.qr_caption);
        qrAddressSmall = view.findViewById(R.id.qr_address_small);
        qrStatusText = view.findViewById(R.id.qr_status_text);
        qrStatusDot = view.findViewById(R.id.qr_status_dot);
        qrAddressValue = view.findViewById(R.id.qr_address_value);
        qrSecurityValue = view.findViewById(R.id.qr_security_value);
        myQrContent = view.findViewById(R.id.my_qr_content);
        scanQrContent = view.findViewById(R.id.scan_qr_content);
        tabMyQrLabel = view.findViewById(R.id.tab_my_qr_label);
        tabScanQrLabel = view.findViewById(R.id.tab_scan_qr_label);
        tabMyQrUnderline = view.findViewById(R.id.tab_my_qr_underline);
        tabScanQrUnderline = view.findViewById(R.id.tab_scan_qr_underline);
        recentScansContainer = view.findViewById(R.id.recent_scans_container);
        recentScansEmpty = view.findViewById(R.id.recent_scans_empty_text);

        view.findViewById(R.id.tab_my_qr).setOnClickListener(v -> selectTab(true));
        view.findViewById(R.id.tab_scan_qr).setOnClickListener(v -> selectTab(false));

        view.findViewById(R.id.edit_server_button).setOnClickListener(v -> goToSettingsTab());
        view.findViewById(R.id.share_button).setOnClickListener(v -> shareQr());
        view.findViewById(R.id.save_qr_button).setOnClickListener(v -> saveQr());
        view.findViewById(R.id.start_scan_button).setOnClickListener(v -> launchScanner());

        view.findViewById(R.id.history_button).setOnClickListener(v -> scrollToRecentScans());
        view.findViewById(R.id.overflow_button).setOnClickListener(v -> {
            if (getContext() != null) {
                startActivity(new Intent(getContext(), ServersListActivity.class));
            }
        });
        view.findViewById(R.id.see_all_scans_button).setOnClickListener(v -> {
            if (getContext() != null) {
                startActivity(new Intent(getContext(), ServersListActivity.class));
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshRunnable.run();
        refreshRecentScans();
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
    }

    private void goToSettingsTab() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).goToPage(MainPagerAdapter.PAGE_SETTINGS);
        }
    }

    private void scrollToRecentScans() {
        View card = rootView.findViewById(R.id.recent_scans_card);
        ScrollView scrollView = findScrollViewParent(card);
        if (scrollView != null) {
            scrollView.post(() -> scrollView.smoothScrollTo(0, card.getTop()));
        }
    }

    private ScrollView findScrollViewParent(View v) {
        android.view.ViewParent parent = v.getParent();
        while (parent != null) {
            if (parent instanceof ScrollView) return (ScrollView) parent;
            parent = parent.getParent();
        }
        return null;
    }

    private void selectTab(boolean myQr) {
        myQrContent.setVisibility(myQr ? View.VISIBLE : View.GONE);
        scanQrContent.setVisibility(myQr ? View.GONE : View.VISIBLE);

        Context context = getContext();
        if (context == null) return;
        int accent = context.getColor(R.color.home_accent);
        int secondary = context.getColor(R.color.home_text_secondary);

        tabMyQrLabel.setTextColor(myQr ? accent : secondary);
        tabScanQrLabel.setTextColor(myQr ? secondary : accent);
        tabMyQrUnderline.setBackgroundColor(myQr ? accent : Color.TRANSPARENT);
        tabScanQrUnderline.setBackgroundColor(myQr ? Color.TRANSPARENT : accent);
    }

    // ---- My QR ----

    private void refreshMyQr() {
        if (getContext() == null) return;

        boolean running = ServerService.isServerRunning();
        String password = ContentStore.getPassword(getContext());
        qrSecurityValue.setText(password.isEmpty() ? "None" : "Password");

        if (!running) {
            currentServerUrl = null;
            qrAddressSmall.setText("Not running");
            qrStatusDot.setBackgroundTintList(getContext().getColorStateList(R.color.offline_red));
            qrStatusText.setText("Offline");
            qrStatusText.setTextColor(getContext().getColor(R.color.home_text_secondary));
            qrCaption.setText("Start the server to generate a QR code");
            qrAddressValue.setText("--");
            qrImage.setImageBitmap(null);
            currentQrBitmap = null;
            return;
        }

        String ip = NetworkUtils.getLocalIpAddress();
        if (ip == null) {
            qrCaption.setText("Connect to Wi-Fi to get an address");
            return;
        }

        currentServerUrl = "http://" + ip + ":" + ServerService.PORT;
        qrAddressSmall.setText(ip + ":" + ServerService.PORT);
        qrAddressValue.setText(ip + ":" + ServerService.PORT);
        qrStatusDot.setBackgroundTintList(getContext().getColorStateList(R.color.online_green));
        qrStatusText.setText("Running");
        qrStatusText.setTextColor(getContext().getColor(R.color.home_accent));
        qrCaption.setText("Scan this QR code to connect to this server on the same network.");

        currentQrBitmap = generateQrBitmap(currentServerUrl, 600);
        qrImage.setImageBitmap(currentQrBitmap);
    }

    private Bitmap generateQrBitmap(String content, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private void shareQr() {
        Context context = getContext();
        if (context == null) return;
        if (currentQrBitmap == null || currentServerUrl == null) {
            Toast.makeText(context, "Start the server first", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File cacheDir = ContentStore.getViewCacheDir(context);
            File qrFile = new File(cacheDir, "phoneserver_qr.png");
            try (FileOutputStream out = new FileOutputStream(qrFile)) {
                currentQrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            Uri contentUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", qrFile);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, contentUri);
            intent.putExtra(Intent.EXTRA_TEXT, "Connect to my PhoneServer: " + currentServerUrl);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share server QR"));
        } catch (Exception e) {
            Toast.makeText(context, "Could not share QR code", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveQr() {
        Context context = getContext();
        if (context == null) return;
        if (currentQrBitmap == null) {
            Toast.makeText(context, "Start the server first", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File dir = ContentStore.getDownloadsDir(context);
            File dest = new File(dir, "phoneserver_qr_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream out = new FileOutputStream(dest)) {
                currentQrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            ContentStore.scanFile(context, dest);
            Toast.makeText(context, "Saved: " + dest.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, "Could not save QR code", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Scan QR ----

    private void launchScanner() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("Point the camera at another phone's QR code");
        options.setBeepEnabled(true);
        options.setOrientationLocked(true);
        scanLauncher.launch(options);
    }

    private void handleScanResult(ScanIntentResult result) {
        if (result.getContents() == null) return; // user cancelled
        Context context = getContext();
        if (context == null) return;

        String scanned = result.getContents().trim();
        String ip;
        int port;
        try {
            URI uri = new URI(scanned);
            ip = uri.getHost();
            port = uri.getPort() > 0 ? uri.getPort() : 8080;
            if (ip == null) throw new IllegalArgumentException("no host");
        } catch (Exception e) {
            Toast.makeText(context, "That QR code isn't a PhoneServer address", Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(context, AddServerActivity.class);
        intent.putExtra(AddServerActivity.EXTRA_PREFILL_IP, ip);
        intent.putExtra(AddServerActivity.EXTRA_PREFILL_PORT, port);
        startActivity(intent);
        selectTab(true);
    }

    // ---- Recent Scans ----

    private void refreshRecentScans() {
        Context context = getContext();
        if (context == null || recentScansContainer == null) return;
        recentScansContainer.removeAllViews();

        List<ServerProfile> profiles = ServerProfileStore.getAll(context);
        recentScansEmpty.setVisibility(profiles.isEmpty() ? View.VISIBLE : View.GONE);

        for (ServerProfile profile : profiles) {
            addRecentScanRow(profile);
        }
    }

    private void addRecentScanRow(ServerProfile profile) {
        Context context = getContext();
        if (context == null) return;

        View row = LayoutInflater.from(context).inflate(R.layout.item_recent_scan, recentScansContainer, false);
        TextView name = row.findViewById(R.id.scan_name);
        TextView address = row.findViewById(R.id.scan_address);
        TextView statusPill = row.findViewById(R.id.scan_status_pill);
        TextView menuButton = row.findViewById(R.id.scan_menu);

        name.setText(profile.name);
        address.setText(profile.ip + ":" + profile.port);
        statusPill.setText("Checking");
        statusPill.setTextColor(context.getColor(R.color.home_text_secondary));

        row.setOnClickListener(v -> {
            Intent intent = new Intent(context, RemoteBrowseActivity.class);
            intent.putExtra("profile_id", profile.id);
            intent.putExtra("path", "");
            startActivity(intent);
        });

        menuButton.setOnClickListener(v -> showScanRowMenu(profile));

        recentScansContainer.addView(row);

        new Thread(() -> {
            boolean online = RemoteApiClient.isOnline(profile);
            handler.post(() -> {
                if (getContext() == null) return;
                statusPill.setText(online ? "Connected" : "Offline");
                statusPill.setTextColor(getContext().getColor(online ? R.color.home_accent : R.color.home_text_secondary));
            });
        }).start();
    }

    private void showScanRowMenu(ServerProfile profile) {
        Context context = getContext();
        if (context == null) return;
        String[] options = {"Connect", "Manage in Server List", "Remove"};
        new AlertDialog.Builder(context)
                .setTitle(profile.name)
                .setItems(options, (dialog, which) -> {
                    switch (options[which]) {
                        case "Connect":
                            Intent intent = new Intent(context, RemoteBrowseActivity.class);
                            intent.putExtra("profile_id", profile.id);
                            intent.putExtra("path", "");
                            startActivity(intent);
                            break;
                        case "Manage in Server List":
                            startActivity(new Intent(context, ServersListActivity.class));
                            break;
                        case "Remove":
                            ServerProfileStore.delete(context, profile.id);
                            refreshRecentScans();
                            break;
                    }
                })
                .show();
    }
}

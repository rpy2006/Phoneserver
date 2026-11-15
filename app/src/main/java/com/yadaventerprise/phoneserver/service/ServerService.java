package com.yadaventerprise.phoneserver.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import com.yadaventerprise.phoneserver.ads.AdNetworkManager;
import com.yadaventerprise.phoneserver.data.stats.ServerStats;
import com.yadaventerprise.phoneserver.data.webhooks.WebhookDispatcher;
import com.yadaventerprise.phoneserver.network.http.SimpleHttpServer;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfig;
import com.yadaventerprise.phoneserver.network.tls.HttpsConfigStore;
import com.yadaventerprise.phoneserver.network.tls.TlsCertificateStore;
import com.yadaventerprise.phoneserver.network.tls.TlsContextFactory;
import com.yadaventerprise.phoneserver.network.tls.TlsIdentity;
import com.yadaventerprise.phoneserver.ui.main.MainActivity;
import com.yadaventerprise.phoneserver.util.IconSwitcher;
import com.yadaventerprise.phoneserver.util.NetworkUtils;
import javax.net.ssl.SSLContext;
import org.json.JSONObject;

public class ServerService extends Service {

    public static final String CHANNEL_ID = "phone_server_channel";
    public static final int NOTIFICATION_ID = 1001;
    public static final int PORT = 8080;

    public static final String ACTION_START = "com.yadaventerprise.phoneserver.START";
    public static final String ACTION_STOP = "com.yadaventerprise.phoneserver.STOP";
    public static final String ACTION_RESTART = "com.yadaventerprise.phoneserver.RESTART";

    private static SimpleHttpServer server;
    private static SimpleHttpServer secureServer;
    private static long startTimeMillis = 0;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            com.yadaventerprise.phoneserver.data.storage.ContentStore.setServerDesiredRunning(this, false);
            stopServer(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        if (ACTION_RESTART.equals(action)) stopServer(false);
        acquireWakeLock();
        startServer();
        return START_STICKY;
    }

    private void startServer() {
        com.yadaventerprise.phoneserver.data.storage.ContentStore.setServerDesiredRunning(this, true);
        if (server == null) {
            server = new SimpleHttpServer(this, PORT);
        }
        if (!server.isRunning()) {
            server.start();
            startTimeMillis = System.currentTimeMillis();
            ServerStats.get().reset();
            ServerStats.get().logServerStarted();
            IconSwitcher.setOnline(this, true);
            JSONObject data = new JSONObject();
            try {
                data.put("port", PORT);
                data.put("https", HttpsConfigStore.get(this).get().enabled);
            } catch (Exception ignored) {
            }
            WebhookDispatcher.emit(this, WebhookDispatcher.EVENT_SERVER_STARTED, data);
        }
        startHttpsIfEnabled();
        AdNetworkManager.startIfEnabled(this);
        BackupScheduler.scheduleNext(this);
        ScheduledTaskScheduler.scheduleNext(this);
        ServerWatchdog.scheduleNext(this);
    }

    private void startHttpsIfEnabled() {
        HttpsConfig config = HttpsConfigStore.get(this).get();
        if (!config.enabled) {
            if (secureServer != null) {
                secureServer.stop();
                secureServer = null;
            }
            return;
        }
        if (secureServer != null && secureServer.isRunning()) return;
        try {
            TlsIdentity identity = TlsCertificateStore.get(this).getOrCreate(this, config);
            SSLContext sslContext = TlsContextFactory.create(identity);
            secureServer = new SimpleHttpServer(this, config.port, sslContext);
            secureServer.start();
        } catch (Exception e) {
            android.util.Log.e("ServerService", "could not start HTTPS server", e);
            secureServer = null;
        }
    }

    private void stopServer(boolean userInitiated) {
        boolean wasRunning = isServerRunning();
        AdNetworkManager.stop();
        if (server != null) {
            server.stop();
        }
        if (secureServer != null) {
            secureServer.stop();
            secureServer = null;
        }
        startTimeMillis = 0;
        releaseWakeLock();
        IconSwitcher.setOnline(this, false);
        if (userInitiated) ServerWatchdog.cancel(this);
        if (wasRunning) {
            JSONObject data = new JSONObject();
            try {
                data.put("userInitiated", userInitiated);
            } catch (Exception ignored) {
            }
            WebhookDispatcher.emit(this, WebhookDispatcher.EVENT_SERVER_STOPPED, data);
        }
    }

    public static boolean isServerRunning() {
        return (server != null && server.isRunning()) || (secureServer != null && secureServer.isRunning());
    }

    public static boolean isHttpsRunning() {
        return secureServer != null && secureServer.isRunning();
    }

    /** Milliseconds the server has been running, or 0 if it's not running. */
    public static long getUptimeMillis() {
        if (!isServerRunning() || startTimeMillis == 0) return 0;
        return System.currentTimeMillis() - startTimeMillis;
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneServer::ServerLock");
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Server Status", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when your phone server is running");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, flags);

        String ip = NetworkUtils.getLocalIpAddress();
        HttpsConfig https = HttpsConfigStore.get(this).get();
        String text = ip != null ? "Running at " + ip + ":" + PORT : "Running on port " + PORT;
        if (https.enabled) text += " · HTTPS " + https.port;

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("PhoneServer is online")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_online)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        stopServer(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

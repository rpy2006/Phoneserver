package com.yadaventerprise.phoneserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class ServerService extends Service {

    public static final String CHANNEL_ID = "phone_server_channel";
    public static final int NOTIFICATION_ID = 1001;
    public static final int PORT = 8080;

    public static final String ACTION_START = "com.yadaventerprise.phoneserver.START";
    public static final String ACTION_STOP = "com.yadaventerprise.phoneserver.STOP";

    private static SimpleHttpServer server;
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
            stopServer();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        acquireWakeLock();
        startServer();
        return START_STICKY;
    }

    private void startServer() {
        if (server == null) {
            server = new SimpleHttpServer(this, PORT);
        }
        if (!server.isRunning()) {
            server.start();
            startTimeMillis = System.currentTimeMillis();
            ServerStats.get().reset();
            ServerStats.get().logServerStarted();
            IconSwitcher.setOnline(this, true);
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
        }
        startTimeMillis = 0;
        releaseWakeLock();
        IconSwitcher.setOnline(this, false);
    }

    public static boolean isServerRunning() {
        return server != null && server.isRunning();
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
        String text = ip != null ? "Running at " + ip + ":" + PORT : "Running on port " + PORT;

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
        stopServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

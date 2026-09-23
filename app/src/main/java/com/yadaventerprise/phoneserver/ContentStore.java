package com.yadaventerprise.phoneserver;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;

/**
 * Single place that knows where all served content lives, and holds the
 * access password used to protect file operations (local server and remote
 * connections both use this same concept).
 */
public class ContentStore {

    public static final String DEFAULT_HTML =
            "<!DOCTYPE html>\n<html>\n<head><meta charset='utf-8'><title>My Phone Server</title></head>\n" +
            "<body style='font-family:sans-serif;background:#0D0F14;color:#F2F4F8;padding:24px'>\n" +
            "<h1>Hello from my Android server</h1>\n" +
            "<p>Edit this page from the PhoneServer app.</p>\n" +
            "</body>\n</html>";

    private static final String API_PREFS_NAME = "api_endpoints";
    private static final String CONFIG_PREFS_NAME = "server_config";
    private static final String KEY_PASSWORD = "access_password";
    private static final String KEY_USERNAME = "auth_username";
    private static final String KEY_START_ON_BOOT = "start_on_boot";
    private static final String KEY_ALLOW_REMOTE_ACCESS = "allow_remote_access";
    private static final String KEY_SHOW_HIDDEN_FILES = "show_hidden_files";

    /**
     * Folder holding everything served at "/" - index.html plus any CSS, JS,
     * images, or other assets the user has uploaded alongside it.
     */
    public static File getWebsiteDir(Context context) {
        File dir = new File(context.getFilesDir(), "website");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** The site's entry point: website/index.html. */
    public static File getWebsiteFile(Context context) {
        return new File(getWebsiteDir(context), "index.html");
    }

    /**
     * Root folder that /files, /api/browse, uploads, etc. all operate under.
     * When "all files access" has been granted, this is the phone's real shared
     * storage root (so DCIM, Pictures, Movies, Download folders and everything
     * else are all reachable) - the same as what Gallery, a music player, or a
     * file manager app would see. Without that permission, we fall back to
     * this app's own private sandbox folder so nothing crashes.
     */
    public static File getPublicFilesDir(Context context) {
        if (hasFullStorageAccess(context)) {
            return Environment.getExternalStorageDirectory();
        }
        File dir = context.getExternalFilesDir("public");
        if (dir == null) {
            dir = new File(context.getFilesDir(), "public");
        }
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Where files pulled down from a remote PhoneServer are saved. */
    public static File getDownloadsDir(Context context) {
        if (hasFullStorageAccess(context)) {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PhoneServer");
            if (!dir.exists()) dir.mkdirs();
            return dir;
        }
        File dir = context.getExternalFilesDir("downloads");
        if (dir == null) {
            dir = new File(context.getFilesDir(), "downloads");
        }
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Scratch space for files opened with "View" before handing off to another app. */
    public static File getViewCacheDir(Context context) {
        File dir = new File(context.getCacheDir(), "view_cache");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static SharedPreferences getApiPrefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(API_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static SharedPreferences getConfigPrefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Empty string means "no password set" - this device's server is open on the local network. */
    public static String getPassword(Context context) {
        return getConfigPrefs(context).getString(KEY_PASSWORD, "");
    }

    public static void setPassword(Context context, String password) {
        getConfigPrefs(context).edit().putString(KEY_PASSWORD, password).apply();
    }

    /** Optional - paired with the password for Basic Auth. Empty means username isn't checked. */
    public static String getUsername(Context context) {
        return getConfigPrefs(context).getString(KEY_USERNAME, "");
    }

    public static void setUsername(Context context, String username) {
        getConfigPrefs(context).edit().putString(KEY_USERNAME, username).apply();
    }

    public static boolean getStartOnBoot(Context context) {
        return getConfigPrefs(context).getBoolean(KEY_START_ON_BOOT, false);
    }

    public static void setStartOnBoot(Context context, boolean enabled) {
        getConfigPrefs(context).edit().putBoolean(KEY_START_ON_BOOT, enabled).apply();
    }

    /** Master switch for the file-management API surface (/files, /api/browse, /api/upload, etc.)
     *  The website ("/") and custom /api/* endpoints keep working either way. */
    public static boolean getAllowRemoteAccess(Context context) {
        return getConfigPrefs(context).getBoolean(KEY_ALLOW_REMOTE_ACCESS, true);
    }

    public static void setAllowRemoteAccess(Context context, boolean enabled) {
        getConfigPrefs(context).edit().putBoolean(KEY_ALLOW_REMOTE_ACCESS, enabled).apply();
    }

    /** When false (the default), files/folders starting with "." are hidden from browsing. */
    public static boolean getShowHiddenFiles(Context context) {
        return getConfigPrefs(context).getBoolean(KEY_SHOW_HIDDEN_FILES, false);
    }

    public static void setShowHiddenFiles(Context context, boolean show) {
        getConfigPrefs(context).edit().putBoolean(KEY_SHOW_HIDDEN_FILES, show).apply();
    }

    /**
     * Resolves a "/"-separated relative path (as sent by a client, e.g. "Pictures/trip")
     * to a File under the public files root, refusing any path that tries to escape
     * that root via "..". Returns null if the path is unsafe.
     */
    public static File resolveSafePath(Context context, String relativePath) {
        return resolveSafePathUnder(getPublicFilesDir(context), relativePath);
    }

    /** Same safety guarantee as resolveSafePath, but rooted at the website folder instead. */
    public static File resolveSafeWebsitePath(Context context, String relativePath) {
        return resolveSafePathUnder(getWebsiteDir(context), relativePath);
    }

    private static File resolveSafePathUnder(File root, String relativePath) {
        if (relativePath == null) relativePath = "";
        relativePath = relativePath.replaceAll("^/+", "");
        if (relativePath.isEmpty()) return root;

        File target = new File(root, relativePath);
        try {
            String rootCanonical = root.getCanonicalPath();
            String targetCanonical = target.getCanonicalPath();
            if (!targetCanonical.equals(rootCanonical)
                    && !targetCanonical.startsWith(rootCanonical + File.separator)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return target;
    }

    /** True if a filename or folder name should be treated as hidden (starts with "."). */
    public static boolean isHiddenName(String name) {
        return name != null && name.startsWith(".");
    }

    // ---- storage permission handling ----

    /** True if this app can read/write anywhere on the device's shared storage. */
    public static boolean hasFullStorageAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Sends the user to the right place to grant full storage access. */
    public static void requestFullStorageAccess(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } catch (Exception e) {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(activity,
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            android.Manifest.permission.READ_EXTERNAL_STORAGE}, 200);
        }
    }

    /** Tells the system media scanner about a new/changed file so Gallery, Music, and Video
     *  apps pick it up immediately instead of waiting for the next full device scan. */
    public static void scanFile(Context context, File file) {
        try {
            MediaScannerConnection.scanFile(context.getApplicationContext(),
                    new String[]{file.getAbsolutePath()}, null, null);
        } catch (Exception ignored) {
        }
    }
}

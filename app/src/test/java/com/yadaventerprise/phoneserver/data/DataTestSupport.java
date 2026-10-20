package com.yadaventerprise.phoneserver.data;

import android.content.Context;
import android.database.sqlite.SQLiteOpenHelper;

import com.yadaventerprise.phoneserver.ContentStore;

import org.junit.Assume;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Locale;

/**
 * Helpers shared by the Robolectric tests.
 *
 * {@link SystemDatabase} is a process-wide singleton, so it must be reset
 * between tests to avoid leaking a connection that points at a deleted
 * application directory. The data root is also pinned to a per-test folder so
 * tests cannot see each other's files.
 */
public final class DataTestSupport {

    private DataTestSupport() {
    }

    /** Closes and clears the {@link SystemDatabase} singleton (simulates an app restart). */
    public static void resetSystemDatabase() {
        try {
            Field field = SystemDatabase.class.getDeclaredField("instance");
            field.setAccessible(true);
            Object current = field.get(null);
            if (current instanceof SQLiteOpenHelper) {
                try {
                    ((SQLiteOpenHelper) current).close();
                } catch (Exception ignored) {
                }
                field.set(null, null);
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not reset SystemDatabase", e);
        }
    }

    /**
     * Skips the current test when the host cannot run Robolectric's SQLite
     * backend. Robolectric ships its native runtime and the legacy sqlite4java
     * bindings only for x86_64 Linux/Windows and x86_64/arm64 macOS; on other
     * platforms (for example an aarch64 Linux host JVM) opening a database
     * throws instead of working. Call this from {@code @Before} before any
     * database is touched so the suite stays green while still exercising the
     * full behaviour on the platforms Robolectric supports.
     */
    public static void assumeRobolectricRuntimeSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean supported;
        if (os.contains("linux")) {
            supported = arch.equals("x86_64") || arch.equals("amd64");
        } else if (os.contains("mac") || os.contains("darwin")) {
            supported = arch.equals("x86_64") || arch.equals("amd64")
                    || arch.equals("aarch64") || arch.equals("arm64");
        } else if (os.contains("win")) {
            supported = arch.equals("x86_64") || arch.equals("amd64");
        } else {
            supported = false;
        }
        Assume.assumeTrue(
                "Robolectric SQLite is unavailable on " + os + "/" + arch,
                supported);
    }

    /** Points the storage layout at a fresh directory and clears the singleton. */
    public static StorageLayout freshDataRoot(Context context, File root) {
        resetSystemDatabase();
        context.deleteDatabase(SystemDatabase.DB_NAME);
        ContentStore.setDataRoot(context, root.getAbsolutePath());
        return ContentStore.getStorageLayout(context);
    }

    /** Convenience: upload a UTF-8 string as a file body. */
    public static java.io.InputStream bytes(String content) {
        return new java.io.ByteArrayInputStream(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}

package com.yadaventerprise.phoneserver.data;

import android.content.Context;
import java.io.File;

/**
 * JSON-based replacement for SystemDatabase. Maintains API compatibility.
 */
public class SystemDatabase {

    public static final String DB_NAME = "system.db";
    public static final int SCHEMA_VERSION = 1;

    private static volatile SystemDatabase instance;
    private final Context context;

    public static SystemDatabase get(Context context) {
        if (instance == null) {
            synchronized (SystemDatabase.class) {
                if (instance == null) {
                    instance = new SystemDatabase(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private SystemDatabase(Context context) {
        this.context = context;
    }

    public void close() {
    }

    public JsonDatabaseWrapper getWritableDatabase() {
        return new JsonDatabaseWrapper(context);
    }

    public JsonDatabaseWrapper getReadableDatabase() {
        return new JsonDatabaseWrapper(context);
    }

    public static long fileSize(File file) {
        return file != null && file.exists() ? file.length() : 0;
    }
}

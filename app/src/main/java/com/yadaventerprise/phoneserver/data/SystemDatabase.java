package com.yadaventerprise.phoneserver.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;

/**
 * The "system" database: metadata for projects, databases, stored files,
 * folders, API tokens, backups and an operation log. Actual file contents and
 * project databases live on the filesystem, not here.
 *
 * WAL journaling and foreign-key enforcement are enabled so concurrent reads by
 * the HTTP server don't block the UI and deletes cascade correctly.
 */
public class SystemDatabase extends SQLiteOpenHelper {

    public static final String DB_NAME = "system.db";
    public static final int SCHEMA_VERSION = 1;

    private static volatile SystemDatabase instance;

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
        super(context, DB_NAME, null, SCHEMA_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE projects ("
                + "id TEXT PRIMARY KEY,"
                + "name TEXT NOT NULL,"
                + "description TEXT NOT NULL DEFAULT '',"
                + "file_quota_bytes INTEGER NOT NULL DEFAULT 0,"
                + "max_upload_bytes INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX idx_projects_name ON projects(name)");

        db.execSQL("CREATE TABLE project_databases ("
                + "id TEXT PRIMARY KEY,"
                + "project_id TEXT NOT NULL,"
                + "name TEXT NOT NULL,"
                + "file_name TEXT NOT NULL,"
                + "size_bytes INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");
        db.execSQL("CREATE UNIQUE INDEX idx_db_project_name ON project_databases(project_id, name)");
        db.execSQL("CREATE INDEX idx_db_project ON project_databases(project_id)");

        db.execSQL("CREATE TABLE files ("
                + "id TEXT PRIMARY KEY,"
                + "project_id TEXT NOT NULL,"
                + "original_name TEXT NOT NULL,"
                + "storage_key TEXT NOT NULL,"
                + "folder_path TEXT NOT NULL DEFAULT '',"
                + "mime_type TEXT NOT NULL DEFAULT 'application/octet-stream',"
                + "size_bytes INTEGER NOT NULL DEFAULT 0,"
                + "checksum TEXT,"
                + "description TEXT NOT NULL DEFAULT '',"
                + "tags TEXT NOT NULL DEFAULT '',"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_files_project ON files(project_id)");
        db.execSQL("CREATE INDEX idx_files_folder ON files(project_id, folder_path)");
        db.execSQL("CREATE UNIQUE INDEX idx_files_storage ON files(storage_key)");

        db.execSQL("CREATE TABLE folders ("
                + "id TEXT PRIMARY KEY,"
                + "project_id TEXT NOT NULL,"
                + "path TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");
        db.execSQL("CREATE UNIQUE INDEX idx_folders_project_path ON folders(project_id, path)");

        db.execSQL("CREATE TABLE api_tokens ("
                + "id TEXT PRIMARY KEY,"
                + "name TEXT NOT NULL,"
                + "token_hash TEXT NOT NULL,"
                + "project_id TEXT,"
                + "can_write INTEGER NOT NULL DEFAULT 1,"
                + "created_at INTEGER NOT NULL,"
                + "last_used_at INTEGER NOT NULL DEFAULT 0,"
                + "revoked INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE UNIQUE INDEX idx_tokens_hash ON api_tokens(token_hash)");

        db.execSQL("CREATE TABLE backups ("
                + "id TEXT PRIMARY KEY,"
                + "project_id TEXT NOT NULL,"
                + "file_name TEXT NOT NULL,"
                + "size_bytes INTEGER NOT NULL DEFAULT 0,"
                + "includes_files INTEGER NOT NULL DEFAULT 1,"
                + "valid INTEGER NOT NULL DEFAULT 0,"
                + "note TEXT NOT NULL DEFAULT '',"
                + "created_at INTEGER NOT NULL,"
                + "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_backups_project ON backups(project_id)");

        db.execSQL("CREATE TABLE operations ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "project_id TEXT,"
                + "action TEXT NOT NULL,"
                + "detail TEXT NOT NULL DEFAULT '',"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_operations_time ON operations(created_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Safe, additive migrations only. Each future version gets its own step
        // so existing user data is preserved across app upgrades.
        if (oldVersion < 2) {
            // reserved for a future schema change
        }
        if (oldVersion < 3) {
            // reserved for a future schema change
        }
    }

    /** Diagnostics used by the system status endpoint. */
    public static long fileSize(File file) {
        return file != null && file.exists() ? file.length() : 0;
    }
}

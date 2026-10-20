package com.yadaventerprise.phoneserver.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain data holders shared between the storage layer, the HTTP API and the UI.
 * Kept in one file so the model is easy to review at a glance.
 */
public final class Records {

    private Records() {
    }

    public static class ProjectRecord {
        public String id;
        public String name;
        public String description = "";
        /** 0 means unlimited. */
        public long fileQuotaBytes = 0;
        /** 0 means use the global default. */
        public long maxUploadBytes = 0;
        public long createdAt;
        public long updatedAt;
    }

    public static class FileRecord {
        public String id;
        public String projectId;
        public String originalName;
        public String storageKey;
        public String folderPath = "";
        public String mimeType = "application/octet-stream";
        public long sizeBytes;
        public String checksum;
        public String description = "";
        public String tags = "";
        public long createdAt;
        public long updatedAt;
    }

    public static class FolderRecord {
        public String id;
        public String projectId;
        public String path;
        public long createdAt;
    }

    public static class DbRecord {
        public String id;
        public String projectId;
        public String name;
        public String fileName;
        public long sizeBytes;
        public long tableCount;
        public long createdAt;
        public long updatedAt;
    }

    public static class BackupRecord {
        public String id;
        public String projectId;
        public String fileName;
        public long sizeBytes;
        public boolean includesFiles;
        public boolean valid;
        public String note = "";
        public long createdAt;
    }

    public static class ApiTokenRecord {
        public String id;
        public String name;
        /** null means the token can access every project. */
        public String projectId;
        public boolean canWrite = true;
        public long createdAt;
        public long lastUsedAt;
        public boolean revoked;
    }

    public static class ColumnInfo {
        public String name;
        public String type;
        public boolean notNull;
        public boolean primaryKey;
        public String defaultValue;
    }

    public static class IndexInfo {
        public String name;
        public boolean unique;
        public List<String> columns = new ArrayList<>();
    }

    public static class TableInfo {
        public String name;
        public String type = "table";
        public long rowCount;
        public List<ColumnInfo> columns = new ArrayList<>();
        public List<IndexInfo> indexes = new ArrayList<>();
    }

    /** Result of executing a SQL statement. */
    public static class QueryResult {
        public boolean isSelect;
        public String keyword;
        public List<String> columns = new ArrayList<>();
        public List<List<Object>> rows = new ArrayList<>();
        public int rowsAffected;
        public boolean truncated;
        public long durationMs;
    }
}

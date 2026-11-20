package com.yadaventerprise.phoneserver.data.storage;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class BackupOptions {
    public static final String TYPE_FULL = "full";
    public static final String TYPE_INCREMENTAL = "incremental";
    public static final String TYPE_RECOVERY = "recovery";

    public static final String CATEGORY_DATABASES = "databases";
    public static final String CATEGORY_FILES = "files";
    public static final String CATEGORY_METADATA = "metadata";
    public static final String CATEGORY_PROJECT_CONFIG = "project_config";

    public String type = TYPE_FULL;
    public boolean includeFiles = true;
    public boolean includeDatabases = true;
    public boolean includeProjectConfig = true;
    public boolean includeMetadata = true;
    public String note = "";
    public char[] password;
    public String parentBackupId;

    public static BackupOptions full(boolean includeFiles, String note) {
        BackupOptions options = new BackupOptions();
        options.type = TYPE_FULL;
        options.includeFiles = includeFiles;
        options.note = note == null ? "" : note;
        return options;
    }

    public static BackupOptions incremental(boolean includeFiles, String note, String parentBackupId) {
        BackupOptions options = full(includeFiles, note);
        options.type = TYPE_INCREMENTAL;
        options.parentBackupId = parentBackupId == null ? "" : parentBackupId;
        return options;
    }

    public Set<String> categories() {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        if (includeDatabases) categories.add(CATEGORY_DATABASES);
        if (includeFiles) categories.add(CATEGORY_FILES);
        if (includeMetadata) categories.add(CATEGORY_METADATA);
        if (includeProjectConfig) categories.add(CATEGORY_PROJECT_CONFIG);
        return Collections.unmodifiableSet(categories);
    }

    public boolean isEncrypted() {
        return password != null && password.length > 0;
    }

    public void clearPassword() {
        if (password == null) return;
        java.util.Arrays.fill(password, '\0');
        password = null;
    }
}

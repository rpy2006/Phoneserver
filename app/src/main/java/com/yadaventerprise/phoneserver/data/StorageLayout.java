package com.yadaventerprise.phoneserver.data;

import java.io.File;
import java.util.UUID;

/**
 * Describes where persistent data lives. All paths are derived from a single
 * configurable data root so application code stays separate from user data and
 * nothing is ever written into temporary build directories.
 *
 * <pre>
 * &lt;root&gt;/
 *   system/system.db
 *   tmp/                          (in-progress uploads / backup scratch)
 *   projects/&lt;project-id&gt;/
 *     databases/&lt;db&gt;.db
 *     files/&lt;storage-key&gt;         (actual file contents, never user-named)
 *     backups/&lt;backup-id&gt;.zip
 *     project-config.json
 * </pre>
 */
public final class StorageLayout {

    public static final String FORMAT_VERSION = "1";

    private final File root;

    public StorageLayout(File root) {
        this.root = root;
    }

    public File root() {
        return root;
    }

    public File systemDir() {
        return new File(root, "system");
    }

    public File systemDb() {
        return new File(systemDir(), "system.db");
    }

    public File tmpDir() {
        return new File(root, "tmp");
    }

    public File projectsDir() {
        return new File(root, "projects");
    }

    public File projectDir(String projectId) {
        return new File(projectsDir(), validateId(projectId));
    }

    public File databasesDir(String projectId) {
        return new File(projectDir(projectId), "databases");
    }

    public File filesDir(String projectId) {
        return new File(projectDir(projectId), "files");
    }

    public File backupsDir(String projectId) {
        return new File(projectDir(projectId), "backups");
    }

    public File projectConfig(String projectId) {
        return new File(projectDir(projectId), "project-config.json");
    }

    /** Actual content location for a stored file (storage keys are generated, never user supplied). */
    public File contentFile(String projectId, String storageKey) {
        if (storageKey == null || !storageKey.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("invalid storage key");
        }
        return new File(filesDir(projectId), storageKey);
    }

    /** Creates the directory if needed and returns it. */
    public static File ensure(File dir) {
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static String generateId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String validateId(String projectId) {
        if (projectId == null || !projectId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("invalid project id");
        }
        return projectId;
    }

    /** A fresh temporary file used while an upload is being streamed to disk. */
    public File newTempFile(String prefix) {
        File dir = ensure(tmpDir());
        return new File(dir, prefix + "-" + generateId() + ".part");
    }
}

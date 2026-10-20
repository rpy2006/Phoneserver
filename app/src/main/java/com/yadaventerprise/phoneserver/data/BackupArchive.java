package com.yadaventerprise.phoneserver.data;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Creates and reads PhoneServer project backup archives.
 *
 * A backup is a zip containing a {@code manifest.properties} file plus
 * {@code databases/*.db}, {@code files/*} and {@code metadata.json}. Extraction
 * refuses absolute paths, "..", and (optionally) oversized contents, so a
 * malicious or corrupt archive can never write outside the restore directory.
 */
public final class BackupArchive {

    public static final String MANIFEST_ENTRY = "manifest.properties";
    public static final String FORMAT = "phoneserver-backup";
    public static final String VERSION = "1";

    /** Default cap for a single restore, to avoid archive bombs. */
    public static final long DEFAULT_MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024;

    private BackupArchive() {
    }

    public static final class Entry {
        public final String name;
        public final File source;

        public Entry(String name, File source) {
            this.name = name;
            this.source = source;
        }
    }

    public static void create(File zipFile, Map<String, String> manifest, List<Entry> entries) throws IOException {
        File parent = zipFile.getParentFile();
        if (parent != null) StorageLayout.ensure(parent);

        File tmp = new File(zipFile.getAbsolutePath() + ".tmp");
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(tmp)))) {
            ZipEntry manifestEntry = new ZipEntry(MANIFEST_ENTRY);
            manifestEntry.setTime(System.currentTimeMillis());
            zos.putNextEntry(manifestEntry);
            Properties props = new Properties();
            for (Map.Entry<String, String> e : manifest.entrySet()) {
                if (e.getValue() != null) props.setProperty(e.getKey(), e.getValue());
            }
            StringBuilder sb = new StringBuilder();
            for (String key : orderedKeys(props)) {
                sb.append(key).append('=').append(props.getProperty(key)).append('\n');
            }
            zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            for (Entry entry : entries) {
                if (!entry.source.exists() || !entry.source.isFile()) continue;
                if (!isSafeEntryName(entry.name)) continue;
                ZipEntry ze = new ZipEntry(entry.name);
                ze.setTime(entry.source.lastModified());
                zos.putNextEntry(ze);
                copy(entry.source, zos);
                zos.closeEntry();
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }

        // Atomic publish only after the archive was written completely.
        if (!tmp.renameTo(zipFile)) {
            if (zipFile.exists() && !zipFile.delete()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException("could not replace existing backup");
            }
            if (!tmp.renameTo(zipFile)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException("could not finalise backup archive");
            }
        }
    }

    /** @return the archive manifest, or throws IOException when the file is not a valid backup. */
    public static Map<String, String> validate(File zipFile) throws IOException {
        if (zipFile == null || !zipFile.exists() || !zipFile.isFile()) {
            throw new IOException("backup file not found");
        }
        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry manifest = zip.getEntry(MANIFEST_ENTRY);
            if (manifest == null) {
                throw new IOException("missing manifest: not a PhoneServer backup");
            }
            Properties props = new Properties();
            try (InputStream in = zip.getInputStream(manifest)) {
                props.load(in);
            }
            if (!FORMAT.equals(props.getProperty("format"))) {
                throw new IOException("unrecognised backup format");
            }
            if (!VERSION.equals(props.getProperty("version"))) {
                throw new IOException("unsupported backup version: " + props.getProperty("version"));
            }
            // Every entry must be a safe relative path.
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) continue;
                if (!isSafeEntryName(entry.getName())) {
                    throw new IOException("unsafe entry in archive: " + entry.getName());
                }
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (String name : props.stringPropertyNames()) {
                result.put(name, props.getProperty(name));
            }
            return result;
        }
    }

    public static List<String> list(File zipFile) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipFile)) {
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                names.add(e.nextElement().getName());
            }
        }
        return names;
    }

    /** Extracts into destDir, refusing unsafe entries and enforcing a total size cap. */
    public static void extract(File zipFile, File destDir, long maxTotalBytes) throws IOException {
        StorageLayout.ensure(destDir);
        long total = 0;
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (!isSafeEntryName(entry.getName())) {
                    throw new IOException("unsafe entry in archive: " + entry.getName());
                }
                File target = PathGuard.safeResolve(destDir, entry.getName());
                if (target == null || !PathGuard.isInside(destDir, target)) {
                    throw new IOException("unsafe entry in archive: " + entry.getName());
                }
                File parent = target.getParentFile();
                if (parent != null) StorageLayout.ensure(parent);
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        total += read;
                        if (total > maxTotalBytes) {
                            throw new IOException("archive too large to restore safely");
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    public static boolean isSafeEntryName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.startsWith("/") || name.startsWith("\\")) return false;
        if (name.contains("\\")) return false;
        return PathGuard.normalizeRelative(name) != null;
    }

    private static List<String> orderedKeys(Properties props) {
        List<String> keys = new ArrayList<>();
        String[] preferred = {"format", "version", "projectId", "projectName", "createdAt",
                "includesFiles", "databaseCount", "fileCount", "appVersion", "note"};
        for (String p : preferred) {
            if (props.containsKey(p)) keys.add(p);
        }
        List<String> rest = new ArrayList<>(props.stringPropertyNames());
        rest.removeAll(keys);
        java.util.Collections.sort(rest);
        keys.addAll(rest);
        return keys;
    }

    private static void copy(File source, OutputStream out) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(source))) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}

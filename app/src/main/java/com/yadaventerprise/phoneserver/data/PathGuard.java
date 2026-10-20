package com.yadaventerprise.phoneserver.data;

import java.io.File;
import java.io.IOException;

/**
 * Path and filename safety for everything that touches the project data root.
 *
 * User-supplied names are never used as filesystem paths directly: they are
 * normalised, validated, and then re-checked against the canonical storage
 * root so a crafted value (e.g. "../../system/system.db") can never escape.
 *
 * This class is deliberately free of any Android dependency so it can be unit
 * tested on a plain JVM.
 */
public final class PathGuard {

    private PathGuard() {
    }

    /**
     * Normalises a client supplied "/"-separated relative folder path.
     *
     * @return the normalised path ("" for the storage root), or null when the
     *         input is unsafe or malformed.
     */
    public static String normalizeRelative(String path) {
        if (path == null) return "";
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) return "";

        StringBuilder out = new StringBuilder();
        for (String segment : p.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return null;
            if (!isValidSegment(segment)) return null;
            if (out.length() > 0) out.append('/');
            out.append(segment);
        }
        return out.toString();
    }

    /** True if a single path segment (file or folder name) is safe to use. */
    public static boolean isValidSegment(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) return false;
        if (trimmed.equals(".") || trimmed.equals("..")) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '/' || c == '\\' || c == '\0' || c == ':' || c < 0x20) return false;
        }
        return true;
    }

    /**
     * Produces a safe file name from a client supplied one: strips directory
     * components, control characters and reserved characters, and never
     * returns an empty string.
     */
    public static String sanitizeFileName(String original) {
        if (original == null) return "file";
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == '\0') continue;
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) return "file";
        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
        return cleaned;
    }

    /** True when a name should be treated as hidden (dot-file). */
    public static boolean isHidden(String name) {
        return name != null && name.startsWith(".");
    }

    /**
     * Resolves a relative path underneath root, returning null when the result
     * would fall outside root (path traversal) or when root is unavailable.
     */
    public static File safeResolve(File root, String relative) {
        if (root == null) return null;
        String normalized = normalizeRelative(relative);
        if (normalized == null) return null;
        try {
            File canonicalRoot = root.getCanonicalFile();
            File target = normalized.isEmpty() ? canonicalRoot : new File(canonicalRoot, normalized);
            File canonicalTarget = target.getCanonicalFile();
            String rootPath = canonicalRoot.getPath();
            String targetPath = canonicalTarget.getPath();
            if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
                return null;
            }
            return canonicalTarget;
        } catch (IOException e) {
            return null;
        }
    }

    /** True when {@code child} is inside {@code root} (both canonicalised). */
    public static boolean isInside(File root, File child) {
        if (root == null || child == null) return false;
        try {
            String rootPath = root.getCanonicalFile().getPath();
            String childPath = child.getCanonicalFile().getPath();
            return childPath.equals(rootPath) || childPath.startsWith(rootPath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }
}

package com.yadaventerprise.phoneserver.data;

import java.util.Locale;

/**
 * Minimal MIME type helpers. Kept dependency-free (no android.webkit.MimeTypeMap)
 * so the same logic runs in unit tests and on the device.
 */
public final class MimeTypes {

    private MimeTypes() {
    }

    public static String guess(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        switch (ext) {
            case "html": case "htm": return "text/html";
            case "css": return "text/css";
            case "js": return "application/javascript";
            case "mjs": return "application/javascript";
            case "json": return "application/json";
            case "xml": return "application/xml";
            case "txt": case "log": case "md": return "text/plain";
            case "csv": return "text/csv";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            case "heic": return "image/heic";
            case "ico": return "image/x-icon";
            case "pdf": return "application/pdf";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "zip": return "application/zip";
            case "gz": return "application/gzip";
            case "tar": return "application/x-tar";
            case "7z": return "application/x-7z-compressed";
            case "rar": return "application/vnd.rar";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "ogg": return "audio/ogg";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "flac": return "audio/flac";
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "mkv": return "video/x-matroska";
            case "mov": return "video/quicktime";
            case "avi": return "video/x-msvideo";
            case "3gp": return "video/3gpp";
            case "apk": return "application/vnd.android.package-archive";
            default: return "application/octet-stream";
        }
    }

    /** Broad category used for metrics and (optionally) organising storage. */
    public static String category(String mime) {
        if (mime == null) return "other";
        if (mime.startsWith("image/")) return "images";
        if (mime.startsWith("video/")) return "videos";
        if (mime.startsWith("audio/")) return "audio";
        if (mime.startsWith("text/") || mime.equals("application/json") || mime.equals("application/xml")
                || mime.equals("application/pdf") || mime.contains("officedocument") || mime.contains("msword")
                || mime.contains("ms-excel") || mime.contains("ms-powerpoint")) {
            return "documents";
        }
        return "other";
    }

    /**
     * Whether a content type is safe to render inline in a browser. Everything
     * else is served as an attachment so uploaded content can never execute in
     * the context of this server.
     */
    public static boolean isInlineSafe(String mime) {
        if (mime == null) return false;
        switch (mime) {
            case "image/png": case "image/jpeg": case "image/gif": case "image/webp":
            case "image/bmp": case "text/plain": case "audio/mpeg": case "audio/mp4":
            case "audio/wav": case "audio/ogg": case "video/mp4": case "video/webm":
            case "application/pdf":
                return true;
            default:
                return false;
        }
    }
}

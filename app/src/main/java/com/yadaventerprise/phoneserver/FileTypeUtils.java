package com.yadaventerprise.phoneserver;

import java.util.Locale;

/** Central place that decides which built-in viewer (if any) handles a given file. */
public class FileTypeUtils {

    public enum Kind { IMAGE, VIDEO, AUDIO, TEXT, PDF, OTHER }

    public static Kind classify(String filename) {
        String ext = extension(filename);
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp":
                return Kind.IMAGE;
            case "mp4": case "3gp": case "mkv": case "webm": case "mov": case "m4v":
                return Kind.VIDEO;
            case "mp3": case "wav": case "m4a": case "aac": case "ogg": case "flac":
                return Kind.AUDIO;
            case "txt": case "md": case "json": case "csv": case "log": case "xml":
            case "html": case "htm": case "css": case "js": case "java": case "gradle":
                return Kind.TEXT;
            case "pdf":
                return Kind.PDF;
            default:
                return Kind.OTHER;
        }
    }

    public static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.US);
    }

    public static String guessMime(String filename) {
        String ext = extension(filename);
        if (ext.isEmpty()) return "*/*";
        String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "*/*";
    }
}

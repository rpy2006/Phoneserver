package com.yadaventerprise.phoneserver;

import java.nio.charset.StandardCharsets;

/**
 * A small, purpose-built parser for the one thing this app needs: pulling a
 * single uploaded file out of a multipart/form-data POST body. Not a general
 * multipart implementation - just enough to read the "file" field reliably.
 */
public class MultipartParser {

    public static class ParsedFile {
        public String filename;
        public byte[] bytes;
    }

    /**
     * @param body     the raw request body bytes (must NOT have passed through a Reader)
     * @param boundary the boundary string from the Content-Type header (without leading --)
     */
    public static ParsedFile parseSingleFile(byte[] body, String boundary) {
        byte[] boundaryMarker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] headerEnd = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

        int partStart = ByteUtils.indexOf(body, boundaryMarker, 0);
        if (partStart < 0) return null;

        int headersStart = partStart + boundaryMarker.length;
        int headersEnd = ByteUtils.indexOf(body, headerEnd, headersStart);
        if (headersEnd < 0) return null;

        String headerText = new String(body, headersStart, headersEnd - headersStart, StandardCharsets.ISO_8859_1);
        String filename = extractFilename(headerText);
        if (filename == null) filename = "upload_" + System.currentTimeMillis();

        int contentStart = headersEnd + headerEnd.length;
        int nextBoundary = ByteUtils.indexOf(body, boundaryMarker, contentStart);
        if (nextBoundary < 0) nextBoundary = body.length;

        // strip the trailing \r\n that precedes the next boundary marker
        int contentEnd = nextBoundary;
        if (contentEnd >= 2 && body[contentEnd - 1] == '\n' && body[contentEnd - 2] == '\r') {
            contentEnd -= 2;
        }
        if (contentEnd < contentStart) contentEnd = contentStart;

        byte[] fileBytes = new byte[contentEnd - contentStart];
        System.arraycopy(body, contentStart, fileBytes, 0, fileBytes.length);

        ParsedFile result = new ParsedFile();
        result.filename = filename;
        result.bytes = fileBytes;
        return result;
    }

    private static String extractFilename(String headerText) {
        int idx = headerText.indexOf("filename=\"");
        if (idx < 0) return null;
        int start = idx + "filename=\"".length();
        int end = headerText.indexOf('"', start);
        if (end < 0) return null;
        String name = headerText.substring(start, end);
        // keep just the last path segment in case a browser sent a full path
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        return name.isEmpty() ? null : name;
    }
}

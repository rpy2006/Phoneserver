package com.yadaventerprise.phoneserver;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ModelStore {

    private static final String TAG = "ModelStore";

    public static final String MODEL_FILENAME = "smollm2-135m-instruct-q8_0.gguf";
    private static final String TEMP_SUFFIX = ".part";

    /** Result of an import or load, carrying a message fit to show in the UI. */
    public static class LoadResult {
        public final boolean success;
        public final String error;

        private LoadResult(boolean success, String error) {
            this.success = success;
            this.error = error;
        }

        static LoadResult ok() {
            return new LoadResult(true, null);
        }

        static LoadResult failure(String error) {
            return new LoadResult(false, error);
        }
    }

    public static File getModelsDir(Context context) {
        File dir = new File(context.getFilesDir(), "models");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getModelFile(Context context) {
        return new File(getModelsDir(context), MODEL_FILENAME);
    }

    public static boolean isModelPresent(Context context) {
        File f = getModelFile(context);
        return f.exists() && f.length() > 0;
    }

    public static void deleteModel(Context context) {
        File f = getModelFile(context);
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "Could not delete model at " + f);
        }
        deleteTempFile(context);
    }

    private static File getTempFile(Context context) {
        return new File(getModelsDir(context), MODEL_FILENAME + TEMP_SUFFIX);
    }

    private static void deleteTempFile(Context context) {
        File tmp = getTempFile(context);
        if (tmp.exists() && !tmp.delete()) {
            Log.w(TAG, "Could not delete partial download at " + tmp);
        }
    }

    /**
     * Copies the picked model into private storage. The copy goes to a .part file
     * and is only renamed into place once it is complete and has been verified to be
     * a text GGUF. Writing straight to the final name meant an interrupted copy of a
     * ~600 MB file left a truncated model that still passed the length > 0 check and
     * then failed to load on every later launch with no way out but re-picking it.
     */
    public static LoadResult importModel(Context context, Uri sourceUri) {
        InputStream in = null;
        File temp = getTempFile(context);
        deleteTempFile(context);

        long sourceLength = -1;
        try {
            in = context.getContentResolver().openInputStream(sourceUri);
            if (in == null) {
                return LoadResult.failure("Could not open the selected file");
            }
            sourceLength = queryLength(context, sourceUri);

            if (sourceLength > 0) {
                long free = freeBytes(context);
                if (free > 0 && free < sourceLength + (16L * 1024 * 1024)) {
                    return LoadResult.failure("Not enough free space to import this model (needs "
                            + formatMb(sourceLength) + ")");
                }
            }

            long copied = copyToTemp(in, temp);
            if (copied <= 0) {
                temp.delete();
                return LoadResult.failure("The selected file was empty");
            }
            if (sourceLength > 0 && copied != sourceLength) {
                temp.delete();
                return LoadResult.failure("The model file was only partially copied ("
                        + formatMb(copied) + " of " + formatMb(sourceLength) + ") - import it again");
            }

            GgufInfo info = GgufInfo.read(temp);
            String rejection = info.rejectionReason();
            if (rejection != null) {
                temp.delete();
                return LoadResult.failure(rejection);
            }

            File dest = getModelFile(context);
            if (dest.exists() && !dest.delete()) {
                Log.w(TAG, "Could not remove previous model at " + dest);
            }
            if (!temp.renameTo(dest)) {
                // renameTo can fail across some storage backends; fall back to a copy.
                try (InputStream src = new java.io.FileInputStream(temp);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = src.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                }
                temp.delete();
            }

            return LoadResult.ok();
        } catch (IOException e) {
            temp.delete();
            Log.e(TAG, "Model import failed", e);
            return LoadResult.failure("Import failed: " + e.getMessage());
        } finally {
            closeQuietly(in);
        }
    }

    public static LoadResult loadIntoEngine(Context context) {
        if (!isModelPresent(context)) {
            return LoadResult.failure("No model found - import a .gguf text model to get started");
        }
        File modelFile = getModelFile(context);

        GgufInfo info = GgufInfo.read(modelFile);
        if (!info.isGenuineGguf) {
            deleteModel(context);
            return LoadResult.failure("The stored model file was incomplete or corrupt and has been removed - import it again");
        }
        String rejection = info.rejectionReason();
        if (rejection != null) {
            return LoadResult.failure(rejection);
        }

        boolean loaded = LlmEngine.get().loadModel(modelFile.getAbsolutePath());
        if (!loaded) {
            return LoadResult.failure("Failed to load the model - it may be an unsupported architecture or quantisation");
        }
        return LoadResult.ok();
    }

    private static long copyToTemp(InputStream in, File temp) throws IOException {
        long copied = 0;
        FileOutputStream out = new FileOutputStream(temp);
        try {
            byte[] buffer = new byte[64 * 1024];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
                copied += len;
            }
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        return copied;
    }

    private static long queryLength(Context context, Uri uri) {
        try (android.database.Cursor cursor = context.getContentResolver()
                .query(uri, new String[]{android.provider.OpenableColumns.SIZE},
                        null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read size of " + uri, e);
        }
        return -1;
    }

    private static long freeBytes(Context context) {
        try {
            StatFs fs = new StatFs(getModelsDir(context).getAbsolutePath());
            return fs.getAvailableBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String formatMb(long bytes) {
        return (bytes / (1024 * 1024)) + " MB";
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Minimal reader for the GGUF header's key/value block. Only the metadata is
     * parsed - no tensors are touched - so it costs a few KB of reads even on a
     * 600 MB file.
     */
    static class GgufInfo {
        /** Thrown when a GGUF header is truncated or contains implausible values. */
        static class BadHeaderException extends IOException {
            BadHeaderException() {
                super("Malformed GGUF header");
            }
        }

        static final String[] TEXT_KEYS = {
                "general.architecture", "general.type", "general.name",
                "general.size_label"
        };

        boolean isGenuineGguf = false;
        String fileName = "";
        final java.util.Map<String, String> values = new java.util.HashMap<>();

        String get(String key) {
            return values.get(key);
        }

        /**
         * Returns a human-readable reason to refuse this file, or null if it looks
         * like a plain text model. The 600 MB file in Download/Office Kit is named
         * smollm2-135m-instruct-q8_0.gguf but its header says it is an "mmproj" -
         * a CLIP vision projector for a Qwen3-VL multimodal model, with no text
         * decoder. Handing that to the inference engine produced an opaque failure
         * that looked like the app dying, so it is now rejected up front by name.
         */
        String rejectionReason() {
            if (!isGenuineGguf) return null;

            String architecture = get("general.architecture");
            String type = get("general.type");
            String sizeLabel = get("general.size_label");

            boolean isVision = "mmproj".equalsIgnoreCase(type)
                    || "clip".equalsIgnoreCase(architecture);
            if (isVision) {
                String label = sizeLabel == null ? "multimodal" : sizeLabel;
                return "\"" + fileName + "\" is a vision projector (mmproj) for a " + label
                        + " image model, not a chat model. Import the matching "
                        + "language-model .gguf file instead.";
            }
            return null;
        }

        static GgufInfo read(File file) {
            GgufInfo info = new GgufInfo();
            info.fileName = file.getName();
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                byte[] magic = new byte[4];
                raf.readFully(magic);
                if (magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
                    return info;
                }
                info.isGenuineGguf = true;

                int version = raf.readInt();
                if (version < 2 || version > 3) return info;

                long tensorCount = raf.readLong();
                long kvCount = raf.readLong();
                if (tensorCount < 0 || kvCount < 0 || kvCount > 4096) return info;

                for (long i = 0; i < kvCount; i++) {
                    String key = readString(raf);
                    if (key == null) return info;
                    Object value = readValue(raf, 0);
                    if (isInteresting(key) && value instanceof String) {
                        info.values.put(key, (String) value);
                    }
                }
            } catch (BadHeaderException e) {
                Log.w(TAG, "Truncated GGUF header in " + file);
            } catch (Exception e) {
                Log.w(TAG, "Could not parse GGUF header of " + file, e);
            }
            return info;
        }

        private static boolean isInteresting(String key) {
            for (String k : TEXT_KEYS) {
                if (k.equals(key)) return true;
            }
            return false;
        }

        private static String readString(java.io.RandomAccessFile raf) throws IOException {
            long len = raf.readLong();
            if (len < 0 || len > 4096) throw new BadHeaderException();
            byte[] bytes = new byte[(int) len];
            raf.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        /** Returns the value for strings; null for every other type. */
        private static Object readValue(java.io.RandomAccessFile raf, int depth) throws IOException {
            if (depth > 4) throw new BadHeaderException();
            int type = raf.readInt();
            switch (type) {
                case 8: // string
                    return readString(raf);
                case 9: { // array - recurse over the elements, we only keep strings
                    int elemType = raf.readInt();
                    long count = raf.readLong();
                    if (count < 0 || count > 10_000_000L) throw new BadHeaderException();
                    for (long i = 0; i < count; i++) {
                        readValue(raf, depth + 1);
                    }
                    return null;
                }
                case 0: case 1: case 7: // u8, i8, bool
                    raf.skipBytes(1);
                    return null;
                case 2: case 3: // u16, i16
                    raf.skipBytes(2);
                    return null;
                case 4: case 5: case 6: // u32, i32, f32
                    raf.skipBytes(4);
                    return null;
                case 10: case 11: case 12: // u64, i64, f64
                    raf.skipBytes(8);
                    return null;
                default:
                    throw new BadHeaderException();
            }
        }
    }
}

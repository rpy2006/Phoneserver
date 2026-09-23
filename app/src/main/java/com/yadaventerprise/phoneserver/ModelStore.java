package com.yadaventerprise.phoneserver;

import android.content.Context;

import java.io.File;

public class ModelStore {

    public static final String MODEL_FILENAME = "smollm2-135m-instruct-q8_0.gguf";

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

    public static void importModel(Context context, android.net.Uri sourceUri) throws Exception {
        File dest = getModelFile(context);
        try (java.io.InputStream in = context.getContentResolver().openInputStream(sourceUri);
             java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
            if (in == null) throw new java.io.IOException("Could not open selected file");
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
        }
    }

    public static boolean loadIntoEngine(Context context) {
        if (!isModelPresent(context)) return false;
        return LlmEngine.get().loadModel(getModelFile(context).getAbsolutePath());
    }
}

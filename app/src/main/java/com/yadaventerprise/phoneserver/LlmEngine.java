package com.yadaventerprise.phoneserver;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

public class LlmEngine {

    private static final String TAG = "LlmEngine";
    private static volatile boolean nativeLibraryAvailable = false;

    static {
        try {
            System.loadLibrary("llama_bridge");
            nativeLibraryAvailable = true;
        } catch (Throwable t) {
            Log.e(TAG, "Native llama_bridge library not available - chat/LLM features disabled", t);
            nativeLibraryAvailable = false;
        }
    }

    public interface GenerationCallback {
        void onToken(String token);
        void onComplete(String fullText);
        void onError(String message);
    }

    private static LlmEngine instance;

    public static synchronized LlmEngine get() {
        if (instance == null) instance = new LlmEngine();
        return instance;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ReentrantLock generationLock = new ReentrantLock();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean modelLoaded = false;
    private long nativeHandle = 0;

    private LlmEngine() {
    }

    public boolean isNativeLibraryAvailable() {
        return nativeLibraryAvailable;
    }

    public boolean isModelLoaded() {
        return modelLoaded;
    }

    public synchronized boolean loadModel(String ggufFilePath) {
        if (!nativeLibraryAvailable) {
            Log.w(TAG, "loadModel() called but native library isn't available");
            return false;
        }
        if (modelLoaded) {
            nativeUnloadModel(nativeHandle);
            modelLoaded = false;
        }
        try {
            nativeHandle = nativeLoadModel(ggufFilePath);
            modelLoaded = nativeHandle != 0;
        } catch (Throwable t) {
            Log.e(TAG, "loadModel() failed", t);
            modelLoaded = false;
        }
        return modelLoaded;
    }

    public synchronized void unloadModel() {
        if (!nativeLibraryAvailable || !modelLoaded) return;
        try {
            nativeUnloadModel(nativeHandle);
        } catch (Throwable t) {
            Log.e(TAG, "unloadModel() failed", t);
        } finally {
            modelLoaded = false;
            nativeHandle = 0;
        }
    }

    public void generate(String prompt, GenerationCallback callback) {
        if (!nativeLibraryAvailable) {
            mainHandler.post(() -> callback.onError("LLM features aren't available on this build"));
            return;
        }
        if (!modelLoaded) {
            mainHandler.post(() -> callback.onError("No model loaded"));
            return;
        }
        executor.execute(() -> {
            generationLock.lock();
            try {
                StringBuilder full = new StringBuilder();
                nativeGenerate(nativeHandle, prompt, token -> {
                    full.append(token);
                    mainHandler.post(() -> callback.onToken(token));
                });
                String result = full.toString();
                mainHandler.post(() -> callback.onComplete(result));
            } catch (Throwable t) {
                Log.e(TAG, "generate() failed", t);
                mainHandler.post(() -> callback.onError(String.valueOf(t.getMessage())));
            } finally {
                generationLock.unlock();
            }
        });
    }

    public String generateBlocking(String prompt) throws Exception {
        if (!nativeLibraryAvailable) throw new IllegalStateException("LLM features aren't available on this build");
        if (!modelLoaded) throw new IllegalStateException("No model loaded");
        generationLock.lock();
        try {
            StringBuilder full = new StringBuilder();
            nativeGenerate(nativeHandle, prompt, full::append);
            return full.toString();
        } finally {
            generationLock.unlock();
        }
    }

    private native long nativeLoadModel(String modelPath);

    private native void nativeUnloadModel(long handle);

    private native void nativeGenerate(long handle, String prompt, TokenSink sink);

    public interface TokenSink {
        void onToken(String token);
    }
}

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

    /**
     * Replaces the loaded model. Holds generationLock for the whole swap: the native
     * unload frees the llama_context and model, so running it while a generation is
     * still decoding from that context reads freed memory and segfaults. loadModel used
     * to take only the instance monitor, which generate() never took, so a re-import
     * from the AI tab could free the context underneath a running answer.
     */
    public boolean loadModel(String ggufFilePath) {
        if (!nativeLibraryAvailable) {
            Log.w(TAG, "loadModel() called but native library isn't available");
            return false;
        }
        generationLock.lock();
        try {
            if (modelLoaded) {
                // Keep the unload inside a try: an abort here would kill the process
                // with no log line at all, and unlike unloadModel() this was unwrapped.
                try {
                    nativeUnloadModel(nativeHandle);
                } catch (Throwable t) {
                    Log.e(TAG, "Unloading the previous model failed", t);
                }
                modelLoaded = false;
                nativeHandle = 0;
            }
            try {
                nativeHandle = nativeLoadModel(ggufFilePath);
                modelLoaded = nativeHandle != 0;
            } catch (Throwable t) {
                Log.e(TAG, "loadModel() failed", t);
                modelLoaded = false;
            }
            return modelLoaded;
        } finally {
            generationLock.unlock();
        }
    }

    public void unloadModel() {
        if (!nativeLibraryAvailable || !modelLoaded) return;
        generationLock.lock();
        try {
            try {
                nativeUnloadModel(nativeHandle);
            } catch (Throwable t) {
                Log.e(TAG, "unloadModel() failed", t);
            } finally {
                modelLoaded = false;
                nativeHandle = 0;
            }
        } finally {
            generationLock.unlock();
        }
    }

    public void generate(String prompt, GenerationCallback callback) {
        if (!nativeLibraryAvailable) {
            mainHandler.post(() -> deliverError(callback, "LLM features aren't available on this build"));
            return;
        }
        // The handle is read inside the lock below, not here. Checking modelLoaded
        // up front and only using the field later left a window in which a
        // concurrent loadModel() had already freed the native context.
        executor.execute(() -> {
            generationLock.lock();
            try {
                if (!modelLoaded) {
                    mainHandler.post(() -> deliverError(callback, "No model loaded"));
                    return;
                }
                final long handle = nativeHandle;
                StringBuilder full = new StringBuilder();
                nativeGenerate(handle, prompt, token -> {
                    full.append(token);
                    mainHandler.post(() -> deliverToken(callback, token));
                });
                String result = full.toString();
                mainHandler.post(() -> deliverComplete(callback, result));
            } catch (Throwable t) {
                Log.e(TAG, "generate() failed", t);
                mainHandler.post(() -> deliverError(callback, String.valueOf(t.getMessage())));
            } finally {
                generationLock.unlock();
            }
        });
    }

    /**
     * These callbacks run on the main thread and touch the chat fragment's views.
     * A token stream outlives the view that started it, so an exception thrown by a
     * stale or already-destroyed fragment would otherwise propagate out of the
     * Handler and take down the whole process - the server UI included. Swallow it
     * here; the callback's own null-guards are the real fix, this is the backstop.
     */
    private void deliverToken(GenerationCallback callback, String token) {
        try {
            callback.onToken(token);
        } catch (Throwable t) {
            Log.w(TAG, "onToken callback threw - ignored", t);
        }
    }

    private void deliverComplete(GenerationCallback callback, String fullText) {
        try {
            callback.onComplete(fullText);
        } catch (Throwable t) {
            Log.w(TAG, "onComplete callback threw - ignored", t);
        }
    }

    private void deliverError(GenerationCallback callback, String message) {
        try {
            callback.onError(message);
        } catch (Throwable t) {
            Log.w(TAG, "onError callback threw - ignored", t);
        }
    }

    public String generateBlocking(String prompt) throws Exception {
        if (!nativeLibraryAvailable) throw new IllegalStateException("LLM features aren't available on this build");
        generationLock.lock();
        try {
            if (!modelLoaded) throw new IllegalStateException("No model loaded");
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

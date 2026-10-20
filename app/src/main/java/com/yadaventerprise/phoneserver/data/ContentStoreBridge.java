package com.yadaventerprise.phoneserver.data;

import android.content.Context;

import com.yadaventerprise.phoneserver.ContentStore;

import java.io.File;

/**
 * Thin adapter between the storage layer and the app's {@link ContentStore}
 * configuration holder, so the data classes don't reach into UI/config code
 * directly and stay easy to reason about.
 */
public final class ContentStoreBridge {

    private ContentStoreBridge() {
    }

    public static StorageLayout layout(Context context) {
        return ContentStore.getStorageLayout(context);
    }

    public static File dataRoot(Context context) {
        return ContentStore.getDataRoot(context);
    }

    public static long defaultMaxUploadBytes(Context context) {
        return ContentStore.DEFAULT_MAX_UPLOAD_BYTES;
    }
}

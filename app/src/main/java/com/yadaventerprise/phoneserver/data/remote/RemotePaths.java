package com.yadaventerprise.phoneserver.data.remote;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;

public final class RemotePaths {
    private RemotePaths() {
    }

    public static File directory(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "remote");
        if (!dir.exists()) dir.mkdirs();
        SecurityPaths.restrict(dir);
        return dir;
    }

    public static File file(Context context, String name) {
        File file = new File(directory(context), name);
        SecurityPaths.restrict(file);
        return file;
    }
}

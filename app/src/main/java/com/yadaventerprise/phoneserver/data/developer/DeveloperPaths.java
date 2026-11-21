package com.yadaventerprise.phoneserver.data.developer;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;

public final class DeveloperPaths {
    private DeveloperPaths() {}

    public static File developerDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "developer");
        if (!dir.exists()) dir.mkdirs();
        SecurityPaths.restrict(dir);
        return dir;
    }

    public static File extensionsDir(Context context) {
        File dir = new File(developerDir(context), "extensions");
        if (!dir.exists()) dir.mkdirs();
        SecurityPaths.restrict(dir);
        return dir;
    }

    public static File sandboxDir(Context context) {
        File dir = new File(developerDir(context), "sandbox");
        if (!dir.exists()) dir.mkdirs();
        SecurityPaths.restrict(dir);
        return dir;
    }
}

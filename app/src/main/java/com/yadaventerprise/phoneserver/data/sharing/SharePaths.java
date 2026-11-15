package com.yadaventerprise.phoneserver.data.sharing;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;

public final class SharePaths {

    private SharePaths() {
    }

    public static File shareDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "shares");
        if (!dir.exists()) dir.mkdirs();
        SecurityPaths.restrict(dir);
        return dir;
    }

    public static File links(Context context) {
        return new File(shareDir(context), "links.json");
    }

    public static File accessLog(Context context) {
        return new File(shareDir(context), "access-log.jsonl");
    }

    public static File settings(Context context) {
        return new File(shareDir(context), "settings.json");
    }
}

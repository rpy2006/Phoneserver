package com.yadaventerprise.phoneserver.network.tls;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.security.SecurityPaths;
import java.io.File;

public final class NetworkPaths {

    private NetworkPaths() {
    }

    public static File networkDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "network");
        if (!dir.exists()) dir.mkdirs();
        SecurityPaths.restrict(dir);
        return dir;
    }

    public static File httpsConfig(Context context) {
        return new File(networkDir(context), "https-config.json");
    }

    public static File tlsIdentity(Context context) {
        return new File(networkDir(context), "tls-identity.json");
    }
}

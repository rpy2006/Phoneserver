package com.yadaventerprise.phoneserver;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Swaps the home screen launcher icon between the "online" and "offline"
 * variants by enabling one activity-alias and disabling the other - both
 * point at MainActivity, only their declared icon differs.
 */
public class IconSwitcher {

    public static void setOnline(Context context, boolean online) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName onlineAlias = new ComponentName(context, "com.yadaventerprise.phoneserver.AliasOnline");
            ComponentName offlineAlias = new ComponentName(context, "com.yadaventerprise.phoneserver.AliasOffline");

            pm.setComponentEnabledSetting(
                    online ? onlineAlias : offlineAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);

            pm.setComponentEnabledSetting(
                    online ? offlineAlias : onlineAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {
            // Icon switching is a cosmetic nicety - never let it crash the server.
        }
    }
}

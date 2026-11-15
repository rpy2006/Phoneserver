package com.yadaventerprise.phoneserver.data.security;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import com.yadaventerprise.phoneserver.data.database.Records;
import com.yadaventerprise.phoneserver.data.storage.BackupSchedule;
import com.yadaventerprise.phoneserver.data.storage.BackupScheduleStore;
import com.yadaventerprise.phoneserver.data.storage.ContentStore;
import com.yadaventerprise.phoneserver.data.tokens.TokenStore;
import com.yadaventerprise.phoneserver.service.ServerService;

/** Snapshot of real configuration used by the security scanner. */
public class SecurityConfig {
    public boolean passwordSet;
    public int passwordLength;
    public boolean usernameSet;
    public boolean allowRemoteAccess;
    public boolean showHiddenFiles;
    public String allowedOrigins = "";
    public int rateLimitPerMinute;
    public boolean serverRunning;
    public boolean allowBackup;
    public boolean debuggable;
    public boolean usesCleartextTraffic;
    public boolean fullStorageAccess;
    public int tokenCount;
    public int writeTokenCount;
    public int unscopedWriteTokenCount;
    public int adminTokenCount;
    public int tokensWithoutRateLimit;
    public int blockedIpCount;
    public int recentIntrusionCount;
    public boolean backupScheduleEnabled;
    public long lastBackupAt;
    public String lastBackupError = "";

    public static SecurityConfig collect(Context context) {
        SecurityConfig config = new SecurityConfig();
        String password = ContentStore.getPassword(context);
        config.passwordSet = password != null && !password.isEmpty();
        config.passwordLength = password == null ? 0 : password.length();
        config.usernameSet = !ContentStore.getUsername(context).isEmpty();
        config.allowRemoteAccess = ContentStore.getAllowRemoteAccess(context);
        config.showHiddenFiles = ContentStore.getShowHiddenFiles(context);
        String origins = ContentStore.getAllowedOrigins(context);
        config.allowedOrigins = origins == null ? "" : origins;
        config.rateLimitPerMinute = ContentStore.getApiRateLimitPerMinute(context);
        config.serverRunning = ServerService.isServerRunning();
        config.fullStorageAccess = ContentStore.hasFullStorageAccess(context);

        ApplicationInfo info = context.getApplicationInfo();
        config.allowBackup = (info.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0;
        config.debuggable = (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        config.usesCleartextTraffic = (info.flags & ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0;

        TokenPolicyStore policyStore = TokenPolicyStore.get(context);
        for (Records.ApiTokenRecord token : TokenStore.list(context)) {
            if (token.revoked) continue;
            config.tokenCount++;
            TokenPolicy policy = policyStore.get(token.id, token);
            if (policy.canWrite()) {
                config.writeTokenCount++;
                if (token.projectId == null) config.unscopedWriteTokenCount++;
            }
            if (policy.isAdmin()) config.adminTokenCount++;
            if (policy.rateLimitPerMinute == 0) config.tokensWithoutRateLimit++;
        }

        config.blockedIpCount = IpBlockStore.get(context).countActive();
        config.recentIntrusionCount = SecurityEventStore.get(context)
                .countSince(System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L);

        BackupSchedule schedule = BackupScheduleStore.get(context).get();
        config.backupScheduleEnabled = schedule.enabled;
        config.lastBackupAt = schedule.lastRunAt;
        config.lastBackupError = schedule.lastError == null ? "" : schedule.lastError;
        return config;
    }
}

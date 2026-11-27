package com.yadaventerprise.phoneserver.data.ai;

import static org.junit.Assert.assertEquals;

import com.yadaventerprise.phoneserver.data.security.TokenPolicy;
import org.junit.Test;

public class AiActionCatalogPermissionTest {

    @Test
    public void returnsWritePermissionsWithoutRecursion() {
        assertEquals(TokenPolicy.BACKUPS_WRITE,
                AiActionCatalog.requiredPermission(AiActionCatalog.BACKUP_CREATE));
        assertEquals(TokenPolicy.FILES_WRITE,
                AiActionCatalog.requiredPermission(AiActionCatalog.FILES_CREATE_FOLDER));
    }

    @Test
    public void returnsReadPermissionsForReadActions() {
        assertEquals(TokenPolicy.FILES_READ,
                AiActionCatalog.requiredPermission(AiActionCatalog.FILES_LIST));
        assertEquals(TokenPolicy.SYSTEM_READ,
                AiActionCatalog.requiredPermission(AiActionCatalog.SERVER_STATUS));
    }
}

package com.yadaventerprise.phoneserver.data.reliability;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecoveryGuardTest {

    @Test
    public void allowsOnlyConfirmedRestoreFromVerifiedBackup() {
        assertTrue(RecoveryGuard.evaluate(true, true, true).allowed);
    }

    @Test
    public void blocksUnverifiedOrUnconfirmedRestore() {
        assertFalse(RecoveryGuard.evaluate(true, false, true).allowed);
        assertFalse(RecoveryGuard.evaluate(true, true, false).allowed);
    }
}

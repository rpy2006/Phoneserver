package com.yadaventerprise.phoneserver.data.reliability;

public final class RecoveryGuard {

    private RecoveryGuard() {
    }

    public static Decision evaluate(boolean currentDataValid, boolean backupValid, boolean confirmed) {
        Decision decision = new Decision();
        if (!backupValid) {
            decision.reason = "backup failed verification";
            return decision;
        }
        if (!confirmed) {
            decision.reason = "restore requires explicit confirmation";
            return decision;
        }
        decision.allowed = true;
        decision.reason = currentDataValid
                ? "confirmed restore over valid data"
                : "confirmed recovery of invalid data";
        return decision;
    }

    public static final class Decision {
        public boolean allowed;
        public String reason = "";
    }
}

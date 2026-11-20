package com.yadaventerprise.phoneserver.data.storage;

public final class RestoreOptions {
    public boolean confirm;
    public boolean createRecoveryPoint = true;
    public boolean restoreDatabases = true;
    public boolean restoreFiles = true;
    public boolean restoreProjectConfig = true;
    public char[] password;

    public static RestoreOptions confirmed(char[] password) {
        RestoreOptions options = new RestoreOptions();
        options.confirm = true;
        options.password = password;
        return options;
    }

    public boolean isEncryptedPasswordProvided() {
        return password != null && password.length > 0;
    }

    public void clearPassword() {
        if (password == null) return;
        java.util.Arrays.fill(password, '\0');
        password = null;
    }
}

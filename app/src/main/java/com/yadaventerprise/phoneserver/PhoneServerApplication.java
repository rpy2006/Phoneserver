package com.yadaventerprise.phoneserver;

import android.app.Application;
import com.yadaventerprise.phoneserver.data.system.CrashDiagnosticStore;
import com.yadaventerprise.phoneserver.data.system.SystemEventStore;
import java.util.Locale;

public final class PhoneServerApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PhoneServerApplicationHolder.set(this);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(previous));
        SystemEventStore.get(this).info("startup", "Application.onCreate completed");
        CrashDiagnosticStore.get(this).append("startup", "Application.onCreate completed");
    }

    private static final class CrashHandler implements Thread.UncaughtExceptionHandler {

        private final Thread.UncaughtExceptionHandler previous;

        CrashHandler(Thread.UncaughtExceptionHandler previous) {
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            Throwable cause = throwable == null ? new IllegalStateException("unknown crash") : throwable;
            String type = cause.getClass().getSimpleName();
            String message = cause.getMessage() == null ? "" : cause.getMessage();
            String detail = String.format(Locale.US, "%s on %s: %s", type, thread == null ? "unknown" : thread.getName(), message);
            try {
                PhoneServerApplication application = PhoneServerApplicationHolder.get();
                if (application != null) {
                    CrashDiagnosticStore.get(application).append("crash", detail);
                    SystemEventStore.get(application).error("crash", detail);
                }
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, cause);
            else Runtime.getRuntime().halt(1);
        }
    }

    private static final class PhoneServerApplicationHolder {
        private static volatile PhoneServerApplication instance;

        static void set(PhoneServerApplication application) {
            instance = application;
        }

        static PhoneServerApplication get() {
            return instance;
        }
    }
}

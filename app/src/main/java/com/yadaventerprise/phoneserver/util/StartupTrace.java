package com.yadaventerprise.phoneserver.util;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

public final class StartupTrace {
    private StartupTrace() {
    }

    public static void write(Context context, String event) {
        File[] dirs = {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStorageDirectory(),
                context.getExternalFilesDir(null),
                context.getFilesDir()
        };
        for (File dir : dirs) {
            if (dir == null) continue;
            try {
                File trace = new File(dir, "phoneserver-startup-trace.log");
                FileOutputStream out = new FileOutputStream(trace, true);
                out.write((System.currentTimeMillis() + " " + event + "\n").getBytes(StandardCharsets.UTF_8));
                out.close();
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    public static void write(Context context, String event, Throwable failure) {
        StringWriter writer = new StringWriter();
        PrintWriter printer = new PrintWriter(writer);
        failure.printStackTrace(printer);
        printer.flush();
        write(context, event + "\n" + writer);
    }
}

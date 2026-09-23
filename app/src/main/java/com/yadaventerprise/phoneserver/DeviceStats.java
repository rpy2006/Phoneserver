package com.yadaventerprise.phoneserver;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Process;

/**
 * Real device resource readings.
 *
 * RAM is genuinely device-wide and accurate - Android exposes this directly.
 *
 * CPU is a real measurement, but of THIS APP'S process only, not the whole
 * device: modern Android (8+) blocks apps from reading system-wide CPU stats
 * without root, so a true "device CPU %" isn't available here. Rather than
 * fake a number, this reports actual CPU time consumed by PhoneServer itself
 * between two samples, which is a meaningful (if narrower) real measurement -
 * it goes up when the server is actively serving requests.
 */
public class DeviceStats {

    private static long lastCpuTimeMillis = 0;
    private static long lastSampleAtMillis = 0;

    public static int getRamUsedPercent(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            long used = info.totalMem - info.availMem;
            return (int) ((used * 100L) / info.totalMem);
        } catch (Exception e) {
            return 0;
        }
    }

    /** App-process CPU usage percent since the last call to this method (0 on the first call). */
    public static int getAppCpuPercent() {
        try {
            long now = System.currentTimeMillis();
            long cpuTime = Process.getElapsedCpuTime(); // this app's total CPU time so far, in ms

            int percent = 0;
            if (lastSampleAtMillis > 0) {
                long wallDelta = now - lastSampleAtMillis;
                long cpuDelta = cpuTime - lastCpuTimeMillis;
                int cores = Math.max(Runtime.getRuntime().availableProcessors(), 1);
                if (wallDelta > 0) {
                    percent = (int) ((cpuDelta * 100L) / (wallDelta * cores));
                    percent = Math.max(0, Math.min(percent, 100));
                }
            }

            lastCpuTimeMillis = cpuTime;
            lastSampleAtMillis = now;
            return percent;
        } catch (Exception e) {
            return 0;
        }
    }
}

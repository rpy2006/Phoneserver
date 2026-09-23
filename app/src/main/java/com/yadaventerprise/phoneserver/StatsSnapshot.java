package com.yadaventerprise.phoneserver;

import java.util.ArrayList;
import java.util.List;

/** A single point-in-time read of a server's real stats - either read directly (local server) or fetched over HTTP (remote server). */
public class StatsSnapshot {
    public long requestCount;
    public long bytesUp;
    public long bytesDown;
    public int activeClients;
    public int ramUsedPercent;
    public int appCpuPercent;
    public long uptimeMillis;

    public List<ClientEntry> clients = new ArrayList<>();
    public List<EndpointEntry> topEndpoints = new ArrayList<>();
    public List<ActivityEntry> recentActivity = new ArrayList<>();

    public static class ClientEntry {
        public String ip, os, browser;
        public long lastSeen;
    }

    public static class EndpointEntry {
        public String path;
        public int count;
    }

    public static class ActivityEntry {
        public String type, message;
        public long time;
    }

    public long totalBytes() {
        return bytesUp + bytesDown;
    }
}

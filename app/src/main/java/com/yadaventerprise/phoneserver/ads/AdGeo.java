package com.yadaventerprise.phoneserver.ads;

import android.content.Context;
import com.yadaventerprise.phoneserver.data.storage.StorageLayout;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** Offline IPv4-to-country lookup. Only country codes are exposed; raw addresses are never stored. */
final class AdGeo {
    private static final class Range {
        final long start, end;
        final String country;

        Range(long start, long end, String country) {
            this.start = start;
            this.end = end;
            this.country = country;
        }
    }

    private static final Object LOCK = new Object();
    private static List<Range> ranges;
    private static long loadedModified = -1;
    private static String loadedVersion = "";

    private AdGeo() {}

    static String country(AdDb db, String ip) {
        if (ip == null || ip.isEmpty() || isPrivate(ip)) return "";
        long v = ipv4(ip);
        if (v < 0) return "ZZ";
        List<Range> table = table(db);
        int lo = 0, hi = table.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Range r = table.get(mid);
            if (v < r.start) hi = mid - 1;
            else if (v > r.end) lo = mid + 1;
            else return r.country;
        }
        return "ZZ";
    }

    private static List<Range> table(AdDb db) {
        synchronized (LOCK) {
            File file = geoFile(db);
            long modified = file.isFile() ? file.lastModified() : 0;
            String version = db.setting("geo_db_version", "none");
            if (ranges != null && modified == loadedModified && version.equals(loadedVersion)) return ranges;
            ranges = load(file);
            loadedModified = modified;
            loadedVersion = version;
            return ranges;
        }
    }

    private static File geoFile(AdDb db) {
        return new File(db.file().getParentFile(), "geoip.csv");
    }

    static JSONObject status(AdDb db) {
        List<Range> table = table(db);
        return AdHttp.obj("version", db.setting("geo_db_version", "none"),
                "updated_at", db.settingLong("geo_last_updated", 0),
                "ranges", table.size(),
                "installed", !table.isEmpty(),
                "format", "start,end,CC or CIDR,CC");
    }

    static void importCsv(AdDb db, String version, byte[] csv) throws IOException {
        if (csv.length == 0 || csv.length > 12 * 1024 * 1024) throw AdHttp.bad("Geo database must be 1 byte to 12 MB");
        List<Range> parsed = parse(csv);
        if (parsed.isEmpty()) throw AdHttp.bad("No valid IPv4 ranges found");
        File file = geoFile(db);
        StorageLayout.ensure(file.getParentFile());
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp." + System.nanoTime());
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(csv);
            out.flush();
            out.getFD().sync();
        }
        if (file.exists() && !file.delete()) throw new IOException("could not replace geo database");
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IOException("could not store geo database");
        }
        synchronized (LOCK) {
            ranges = parsed;
            loadedModified = file.lastModified();
            loadedVersion = version;
        }
        db.setSetting("geo_db_version", version == null || version.isEmpty() ? "custom" : AdSecurity.clean(version));
        db.setSetting("geo_last_updated", String.valueOf(System.currentTimeMillis()));
    }

    private static List<Range> load(File file) {
        if (!file.isFile()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return parse(out.toByteArray());
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static List<Range> parse(byte[] data) {
        List<Range> out = new ArrayList<>();
        String text = new String(data, StandardCharsets.UTF_8);
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("version:") || s.startsWith("updated:")) continue;
            String[] p = s.split("[,;\\t]");
            try {
                if (p.length >= 3) {
                    long start = ipv4(p[0].trim()), end = ipv4(p[1].trim());
                    String cc = p[2].trim().toUpperCase(Locale.ROOT);
                    if (start >= 0 && end >= start && cc.matches("[A-Z]{2}")) out.add(new Range(start, end, cc));
                } else if (p.length == 2) {
                    String net = p[0].trim();
                    String cc = p[1].trim().toUpperCase(Locale.ROOT);
                    int slash = net.indexOf('/');
                    if (slash < 0 || !cc.matches("[A-Z]{2}")) continue;
                    long base = ipv4(net.substring(0, slash));
                    int bits = Integer.parseInt(net.substring(slash + 1));
                    if (base < 0 || bits < 0 || bits > 32) continue;
                    long size = bits == 0 ? 0x1_0000_0000L : (1L << (32 - bits));
                    long start = base & ~(size - 1);
                    out.add(new Range(start, start + size - 1, cc));
                }
            } catch (RuntimeException ignored) {
            }
        }
        out.sort(Comparator.comparingLong(r -> r.start));
        return out;
    }

    static boolean isPrivate(String ip) {
        long v = ipv4(ip);
        if (v < 0) {
            String s = ip.toLowerCase(Locale.ROOT);
            return s.equals("::1") || s.equals("::") || s.startsWith("fc") || s.startsWith("fd")
                    || s.startsWith("fe8") || s.startsWith("fe9") || s.startsWith("fea") || s.startsWith("feb")
                    || s.startsWith("ff") || s.startsWith("2001:db8");
        }
        return v >> 24 == 0 || v >> 24 == 10 || (v >> 22) == 0x19A || (v >> 24) == 127
                || (v >> 16) == 0xA9FE || (v >> 20) == 0xAC1 || (v >> 16) == 0xC0A8
                || (v >> 22) == 0x644 || (v >> 24) >= 224 || (v >> 8) == 0xC000
                || (v >> 8) == 0xC00002 || (v >> 8) == 0xC63364 || (v >> 8) == 0xCB0071;
    }

    static long ipv4(String s) {
        if (s == null) return -1;
        String[] p = s.trim().split("\\.");
        if (p.length != 4) return -1;
        long v = 0;
        for (String part : p) {
            if (!part.matches("[0-9]{1,3}")) return -1;
            int n = Integer.parseInt(part);
            if (n > 255) return -1;
            v = (v << 8) | n;
        }
        return v;
    }
}

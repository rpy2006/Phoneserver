package com.yadaventerprise.phoneserver.data.sharing;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ShareSessionStore {

    public static final long DEFAULT_TTL_MS = 30L * 60L * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private ShareSessionStore() {
    }

    public static String create(String linkId) {
        if (linkId == null || linkId.isEmpty()) return "";
        prune();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder token = new StringBuilder();
        for (byte b : bytes) {
            token.append(Character.forDigit((b >> 4) & 0xF, 16));
            token.append(Character.forDigit(b & 0xF, 16));
        }
        String value = token.toString();
        SESSIONS.put(value, new Session(linkId, System.currentTimeMillis() + DEFAULT_TTL_MS));
        return value;
    }

    public static boolean isValid(String cookieValue, String linkId) {
        if (cookieValue == null || cookieValue.isEmpty() || linkId == null) return false;
        Session session = SESSIONS.get(cookieValue);
        if (session == null) return false;
        if (session.expiresAt <= System.currentTimeMillis()) {
            SESSIONS.remove(cookieValue);
            return false;
        }
        return session.linkId.equals(linkId);
    }

    public static void revokeLink(String linkId) {
        if (linkId == null) return;
        Iterator<Map.Entry<String, Session>> iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().linkId.equals(linkId)) iterator.remove();
        }
    }

    public static void reset() {
        SESSIONS.clear();
    }

    private static void prune() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Session>> iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAt <= now) iterator.remove();
        }
    }

    private static final class Session {
        final String linkId;
        final long expiresAt;

        Session(String linkId, long expiresAt) {
            this.linkId = linkId;
            this.expiresAt = expiresAt;
        }
    }
}

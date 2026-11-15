package com.yadaventerprise.phoneserver.model;

/** A saved remote server the user can connect to (another phone running PhoneServer). */
public class ServerProfile {
    public String id;       // unique id, generated on creation
    public String name;     // friendly label, e.g. "Living Room Phone"
    public String ip;
    public int port = 8080;
    public String username;    // optional - label only, not used for authentication
    public String password;    // may be empty if that server has no password set
    public String description; // optional free-text note
    public String scheme = "http";
    public String token = "";
    public String fingerprint = "";

    public ServerProfile() {
    }

    public String baseUrl() {
        String value = scheme == null || scheme.isEmpty() ? "http" : scheme;
        return value + "://" + ip + ":" + port;
    }

    public boolean usesHttps() {
        return "https".equalsIgnoreCase(scheme);
    }
}

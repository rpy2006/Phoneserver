package com.yadaventerprise.phoneserver;

/** A single file or folder as reported by a remote server's /api/browse endpoint. */
public class RemoteEntry {
    public String name;
    public boolean isDir;
    public long size;
    public long modified;

    public RemoteEntry(String name, boolean isDir, long size, long modified) {
        this.name = name;
        this.isDir = isDir;
        this.size = size;
        this.modified = modified;
    }
}

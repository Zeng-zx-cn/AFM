package com.marv.filemaster;

/**
 * 远程文件条目（FTP / WebDAV 通用）。
 */
public class RemoteFile {
    public final String name;
    public final boolean isDir;
    public final long size;
    public final String time;
    public final String path;

    public RemoteFile(String name, boolean isDir, long size, String time, String path) {
        this.name = name;
        this.isDir = isDir;
        this.size = size;
        this.time = time;
        this.path = path;
    }

    @Override
    public String toString() {
        return name;
    }

    public static String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
    }
}

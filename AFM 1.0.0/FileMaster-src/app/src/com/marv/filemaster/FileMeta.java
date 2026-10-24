package com.marv.filemaster;

/**
 * 文件元数据：目录标志、大小、修改时间。
 * 普通模式由 File 直接取，Root 模式由 ls 输出解析，列表与排序统一使用。
 */
public class FileMeta {
    public boolean isDir;
    public long size;
    public long time;

    public FileMeta(boolean isDir, long size, long time) {
        this.isDir = isDir;
        this.size = size;
        this.time = time;
    }
}

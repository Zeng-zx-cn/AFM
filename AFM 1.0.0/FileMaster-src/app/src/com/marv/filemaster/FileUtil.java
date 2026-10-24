package com.marv.filemaster;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 文件操作工具类：复制、移动、删除、重命名、大小格式化、递归搜索、文件排序。
 */
public final class FileUtil {

    public static final int SORT_NAME = 0;
    public static final int SORT_SIZE = 1;
    public static final int SORT_TIME = 2;
    public static final int SORT_TYPE = 3;

    private static final long KB = 1024;
    private static final long MB = 1024 * KB;
    private static final long GB = 1024 * MB;

    private FileUtil() {}

    /** 列出目录内容，文件夹在前，按名称排序 */
    public static List<File> listFiles(File dir) {
        return listFiles(dir, SORT_NAME);
    }

    /** 列出目录内容并按指定模式排序 */
    public static List<File> listFiles(File dir, int sortMode) {
        List<File> result = new ArrayList<>();
        if (dir == null || !dir.exists() || !dir.isDirectory()) return result;
        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            if (f.isHidden() && !f.getAbsolutePath().startsWith("/data")) continue;
            result.add(f);
        }
        sortFiles(result, sortMode, null);
        return result;
    }

    /** 按模式排序文件列表（文件夹始终在前）。meta 提供 root 模式下的元数据，可为 null。 */
    public static void sortFiles(List<File> files, final int sortMode, final java.util.Map<File, FileMeta> meta) {
        if (files == null) return;
        files.sort((a, b) -> {
            FileMeta ma = meta == null ? null : meta.get(a);
            FileMeta mb = meta == null ? null : meta.get(b);
            boolean da = ma != null ? ma.isDir : a.isDirectory();
            boolean db = mb != null ? mb.isDir : b.isDirectory();
            if (da != db) return da ? -1 : 1;
            switch (sortMode) {
                case SORT_SIZE: {
                    long sa = ma != null ? ma.size : a.length();
                    long sb = mb != null ? mb.size : b.length();
                    if (sa != sb) return sa > sb ? -1 : 1;
                    break;
                }
                case SORT_TIME: {
                    long ta = ma != null ? ma.time : a.lastModified();
                    long tb = mb != null ? mb.time : b.lastModified();
                    if (ta != tb) return ta > tb ? -1 : 1;
                    break;
                }
                case SORT_TYPE: {
                    int cmp = ext(a.getName()).compareTo(ext(b.getName()));
                    if (cmp != 0) return cmp;
                    break;
                }
                default:
                    break;
            }
            return a.getName().toLowerCase(Locale.US).compareTo(b.getName().toLowerCase(Locale.US));
        });
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1).toLowerCase(Locale.US) : "";
    }

    /** 复制文件或目录到目标位置 */
    public static boolean copy(File src, File dstDir) throws IOException {
        if (!src.exists()) return false;
        File target = new File(dstDir, src.getName());
        if (src.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) return false;
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    copy(c, target);
                }
            }
            return true;
        } else {
            if (src.equals(target)) return true;
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
            return true;
        }
    }

    /** 移动（剪切）文件或目录 */
    public static boolean move(File src, File dstDir) throws IOException {
        if (!src.exists()) return false;
        File target = new File(dstDir, src.getName());
        if (src.renameTo(target)) return true;
        boolean ok = copy(src, dstDir);
        if (ok) deleteRecursively(src);
        return ok;
    }

    /** 递归删除文件或目录 */
    public static boolean deleteRecursively(File f) {
        if (!f.exists()) return true;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        return f.delete();
    }

    /** 重命名 */
    public static boolean rename(File f, String newName) {
        if (newName == null || newName.trim().length() == 0) return false;
        File target = new File(f.getParentFile(), newName.trim());
        if (target.exists()) return false;
        return f.renameTo(target);
    }

    /** 新建文件夹 */
    public static boolean newFolder(File parent, String name) {
        if (name == null || name.trim().length() == 0) return false;
        File f = new File(parent, name.trim());
        return f.mkdirs();
    }

    /** 格式化文件大小 */
    public static String formatSize(long size) {
        if (size < KB) return size + " B";
        if (size < MB) return String.format(Locale.US, "%.1f KB", size / (double) KB);
        if (size < GB) return String.format(Locale.US, "%.1f MB", size / (double) MB);
        return String.format(Locale.US, "%.2f GB", size / (double) GB);
    }

    /** 格式化修改时间 */
    public static String formatTime(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(time));
    }

    /** 获取文件大小（目录递归统计） */
    public static long getSize(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                total += getSize(c);
            }
        }
        return total;
    }

    /** 递归搜索：在当前目录下按关键词匹配文件名 */
    public static void search(File dir, String keyword, List<File> result, int maxDepth, int currentDepth) {
        if (dir == null || !dir.isDirectory() || currentDepth > maxDepth) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        String kw = keyword.toLowerCase(Locale.US);
        for (File c : children) {
            if (c.getName().toLowerCase(Locale.US).contains(kw)) {
                result.add(c);
            }
            if (c.isDirectory()) {
                search(c, keyword, result, maxDepth, currentDepth + 1);
            }
        }
    }

    /** 判断是否为文本文件（按扩展名粗判） */
    public static boolean isTextFile(File f) {
        String name = f.getName().toLowerCase(Locale.US);
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".log")
                || name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".html")
                || name.endsWith(".htm") || name.endsWith(".css") || name.endsWith(".js")
                || name.endsWith(".java") || name.endsWith(".py") || name.endsWith(".c")
                || name.endsWith(".cpp") || name.endsWith(".h") || name.endsWith(".sh")
                || name.endsWith(".ini") || name.endsWith(".cfg") || name.endsWith(".conf")
                || name.endsWith(".properties") || name.endsWith(".csv") || name.endsWith(".yml")
                || name.endsWith(".yaml") || name.endsWith(".sql") || name.endsWith(".bat")
                || name.endsWith(".gitignore") || name.endsWith(".kt") || name.endsWith(".gradle")
                || name.endsWith(".rc") || name.endsWith(".plist");
    }

    public static boolean isZipFile(File f) {
        String name = f.getName().toLowerCase(Locale.US);
        return name.endsWith(".zip");
    }

    /** 读取文本内容（尝试 UTF-8） */
    public static String readText(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, len, "UTF-8"));
            }
        }
        return sb.toString();
    }

    /** 写入文本内容 */
    public static void writeText(File f, String content) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes("UTF-8"));
        }
    }
}

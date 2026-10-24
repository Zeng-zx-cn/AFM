package com.marv.filemaster;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ZIP 压缩 / 解压工具（基于 JDK 内置 java.util.zip）。
 */
public final class ZipUtil {

    private ZipUtil() {}

    /** 将多个文件/目录压缩到目标 zip 文件 */
    public static boolean zip(List<File> sources, File zipFile) throws IOException {
        if (zipFile.getParentFile() != null && !zipFile.getParentFile().exists()) {
            zipFile.getParentFile().mkdirs();
        }
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            byte[] buf = new byte[64 * 1024];
            for (File src : sources) {
                if (!src.exists()) continue;
                addToZip(zos, src, src.getName(), buf);
            }
        }
        return true;
    }

    private static void addToZip(ZipOutputStream zos, File file, String entryName, byte[] buf) throws IOException {
        if (file.isDirectory()) {
            ZipEntry entry = new ZipEntry(entryName.endsWith("/") ? entryName : entryName + "/");
            zos.putNextEntry(entry);
            zos.closeEntry();
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) {
                    addToZip(zos, c, entryName + "/" + c.getName(), buf);
                }
            }
        } else {
            zos.putNextEntry(new ZipEntry(entryName));
            try (InputStream in = new FileInputStream(file)) {
                int len;
                while ((len = in.read(buf)) > 0) {
                    zos.write(buf, 0, len);
                }
            }
            zos.closeEntry();
        }
    }

    /** 解压 zip 文件到目标目录 */
    public static boolean unzip(File zipFile, File destDir) throws IOException {
        if (!destDir.exists()) destDir.mkdirs();
        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                // 防路径穿越
                String canonical = outFile.getCanonicalPath();
                String base = destDir.getCanonicalPath();
                if (!canonical.startsWith(base + File.separator) && !canonical.equals(base)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int len;
                        while ((len = zis.read(buf)) > 0) {
                            fos.write(buf, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        return true;
    }
}

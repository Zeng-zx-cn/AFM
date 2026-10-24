package com.marv.filemaster;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Root 权限工具：检测 su、执行命令、以 root 列出受保护目录、执行 root 文件操作。
 * 所有操作通过 su -c 调用 shell 命令完成。
 */
public final class RootShell {

    private static Boolean rootAvailable = null;

    /** ls -la 行解析：权限 | 链接数 | 属主 | 属组 | 大小 | (yyyy-MM-dd HH:mm 或 MMM d HH:mm) | 名称 */
    private static final Pattern LS_LINE = Pattern.compile(
            "^([bcdlps-][rwxstST-]{9})\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+" +
            "(?:(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})|(\\w{3})\\s+(\\d{1,2})\\s+(\\d{2}):(\\d{2}))\\s+(.+)$");

    private RootShell() {}

    /** 检测当前设备是否已授予 root（su 存在且返回 uid=0） */
    public static boolean isRootAvailable() {
        if (rootAvailable != null) return rootAvailable;
        rootAvailable = execOut("id").contains("uid=0");
        return rootAvailable;
    }

    /** 执行一条命令，返回是否成功（exit code == 0） */
    public static boolean exec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            drain(p.getErrorStream());
            int code = p.waitFor();
            drain(p.getInputStream());
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 执行命令并返回标准输出 */
    public static String execOut(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor();
            drain(p.getErrorStream());
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private static void drain(java.io.InputStream in) {
        try {
            byte[] buf = new byte[1024];
            while (in.read(buf) > 0) { /* 排空 */ }
            in.close();
        } catch (Exception ignored) {
        }
    }

    /** 目录条目（由 ls 解析） */
    public static class Entry {
        public File file;
        public boolean isDir;
        public long size;
        public long time;
    }

    /** 以 root 权限列出目录内容（失败返回空列表） */
    public static List<Entry> list(File dir) {
        List<Entry> result = new ArrayList<>();
        String out = execOut("ls -la " + q(dir.getAbsolutePath()));
        String[] lines = out.split("\n");
        for (String line : lines) {
            if (line == null) continue;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("total")) continue;
            Matcher m = LS_LINE.matcher(line);
            if (!m.matches()) continue;
            boolean isDir = m.group(1).startsWith("d");
            long size;
            try {
                size = Long.parseLong(m.group(2));
            } catch (NumberFormatException e) {
                size = 0;
            }
            long time = parseTime(m);
            String name = m.group(12).trim();
            if (name.equals(".") || name.equals("..")) continue;
            int arrow = name.indexOf(" -> ");
            if (arrow >= 0) name = name.substring(0, arrow).trim();
            Entry e = new Entry();
            e.file = new File(dir, name);
            e.isDir = isDir;
            e.size = size;
            e.time = time;
            result.add(e);
        }
        return result;
    }

    private static long parseTime(Matcher m) {
        try {
            if (m.group(3) != null) {
                // yyyy-MM-dd HH:mm
                String s = m.group(3) + "-" + m.group(4) + "-" + m.group(5)
                        + " " + m.group(6) + ":" + m.group(7);
                return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(s).getTime();
            } else {
                // MMM d HH:mm，缺年份 → 取当前年份
                String s = m.group(8) + " " + m.group(9) + " " + m.group(10) + ":" + m.group(11);
                Date d = new SimpleDateFormat("MMM d HH:mm", Locale.US).parse(s);
                Calendar cal = Calendar.getInstance();
                cal.setTime(d);
                cal.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR));
                return cal.getTimeInMillis();
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** root 复制：cp -rf src dstDir */
    public static boolean copy(File src, File dstDir) {
        return exec("cp -rf " + q(src.getAbsolutePath()) + " " + q(dstDir.getAbsolutePath()));
    }

    /** root 移动：mv -f src dstDir */
    public static boolean move(File src, File dstDir) {
        return exec("mv -f " + q(src.getAbsolutePath()) + " " + q(dstDir.getAbsolutePath()));
    }

    /** root 重命名 */
    public static boolean rename(File f, String newName) {
        if (newName == null || newName.trim().length() == 0) return false;
        File target = new File(f.getParentFile(), newName.trim());
        return exec("mv -f " + q(f.getAbsolutePath()) + " " + q(target.getAbsolutePath()));
    }

    /** root 删除 */
    public static boolean delete(File f) {
        return exec("rm -rf " + q(f.getAbsolutePath()));
    }

    /** root 新建文件夹 */
    public static boolean mkdir(File parent, String name) {
        if (name == null || name.trim().length() == 0) return false;
        File f = new File(parent, name.trim());
        return exec("mkdir -p " + q(f.getAbsolutePath()));
    }

    /** root 读取文本（cat） */
    public static String read(File f) {
        return execOut("cat " + q(f.getAbsolutePath()));
    }

    /** root 写入文本（base64 中转，避免转义问题） */
    public static boolean write(File f, String content) {
        try {
            String b64 = Base64.encodeToString(content.getBytes("UTF-8"), Base64.NO_WRAP);
            return exec("echo " + b64 + " | base64 -d > " + q(f.getAbsolutePath()));
        } catch (Exception e) {
            return false;
        }
    }

    /** 判断目录是否可被普通应用直接读取列表 */
    public static boolean directlyReadable(File dir) {
        if (dir == null || !dir.exists()) return false;
        return dir.canRead() && dir.listFiles() != null;
    }

    /** shell 单引号转义 */
    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}

package com.marv.filemaster;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 精简 FTP 客户端：基于 Socket 手工实现 FTP 协议（USER/PASS/PWD/CWD/LIST/PASV/RETR/STOR/DELE/MKD/RNFR/RNTO）。
 * 支持被动模式，兼容大多数 FTP 服务器。
 */
public final class FtpClient {
    private Socket control;
    private BufferedReader reader;
    private BufferedWriter writer;

    public void connect(String host, int port, int timeoutMs) throws IOException {
        control = new Socket();
        control.connect(new InetSocketAddress(host, port), timeoutMs);
        control.setSoTimeout(timeoutMs);
        reader = new BufferedReader(new InputStreamReader(control.getInputStream(), "ISO-8859-1"));
        writer = new BufferedWriter(new OutputStreamWriter(control.getOutputStream(), "ISO-8859-1"));
        String reply = readReply();
        if (reply == null || !reply.startsWith("220")) {
            throw new IOException("服务器未就绪: " + safe(reply));
        }
    }

    public void login(String user, String pass) throws IOException {
        String r = send("USER " + safe(user));
        if (r == null) throw new IOException("连接已关闭");
        if (r.startsWith("331") || r.startsWith("230")) {
            String r2 = send("PASS " + safe(pass));
            if (r2 == null || !r2.startsWith("230")) throw new IOException("登录失败: " + safe(r2));
        } else {
            throw new IOException("登录失败: " + safe(r));
        }
    }

    public String pwd() throws IOException {
        String r = send("PWD");
        if (r == null || !r.startsWith("257")) throw new IOException("PWD 失败: " + safe(r));
        int a = r.indexOf('"');
        int b = r.lastIndexOf('"');
        if (a >= 0 && b > a) return r.substring(a + 1, b);
        return r.length() > 4 ? r.substring(4).trim() : "/";
    }

    public void cwd(String path) throws IOException {
        String r = send("CWD " + safe(path));
        if (r == null || !r.startsWith("250")) throw new IOException("进入目录失败: " + safe(r));
    }

    public List<RemoteFile> list(String path) throws IOException {
        send("TYPE A");
        Socket data = openData();
        String r = send("LIST " + (path == null || path.isEmpty() ? "." : safe(path)));
        if (r == null || (!r.startsWith("150") && !r.startsWith("125"))) {
            closeData(data);
            throw new IOException("列目录失败: " + safe(r));
        }
        List<RemoteFile> out = new ArrayList<>();
        try {
            BufferedReader dr = new BufferedReader(new InputStreamReader(data.getInputStream(), "ISO-8859-1"));
            String line;
            while ((line = dr.readLine()) != null) {
                RemoteFile rf = parseListLine(line);
                if (rf != null) out.add(rf);
            }
            dr.close();
        } finally {
            closeData(data);
        }
        readReply(); // 226
        return out;
    }

    public void download(String remote, File local) throws IOException {
        send("TYPE I");
        Socket data = openData();
        String r = send("RETR " + safe(remote));
        if (r == null || (!r.startsWith("150") && !r.startsWith("125"))) {
            closeData(data);
            throw new IOException("下载失败: " + safe(r));
        }
        try {
            InputStream in = data.getInputStream();
            FileOutputStream fos = new FileOutputStream(local);
            byte[] buf = new byte[65536];
            int n;
            try {
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            } finally {
                fos.close();
                in.close();
            }
        } finally {
            closeData(data);
        }
        readReply(); // 226
    }

    public void upload(File local, String remote) throws IOException {
        send("TYPE I");
        Socket data = openData();
        String r = send("STOR " + safe(remote));
        if (r == null || (!r.startsWith("150") && !r.startsWith("125"))) {
            closeData(data);
            throw new IOException("上传失败: " + safe(r));
        }
        try {
            OutputStream out = data.getOutputStream();
            FileInputStream fis = new FileInputStream(local);
            byte[] buf = new byte[65536];
            int n;
            try {
                while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                fis.close();
                out.close();
            }
        } finally {
            closeData(data);
        }
        readReply(); // 226
    }

    public void delete(String path) throws IOException {
        String r = send("DELE " + safe(path));
        if (r == null || !r.startsWith("250")) throw new IOException("删除失败: " + safe(r));
    }

    public void mkdir(String path) throws IOException {
        String r = send("MKD " + safe(path));
        if (r == null || (!r.startsWith("257") && !r.startsWith("250"))) throw new IOException("新建目录失败: " + safe(r));
    }

    public void rename(String from, String to) throws IOException {
        String r = send("RNFR " + safe(from));
        if (r == null || !r.startsWith("350")) throw new IOException("重命名失败: " + safe(r));
        String r2 = send("RNTO " + safe(to));
        if (r2 == null || !r2.startsWith("250")) throw new IOException("重命名失败: " + safe(r2));
    }

    public void disconnect() {
        try {
            if (isConnected()) send("QUIT");
        } catch (Exception ignored) {
        }
        try {
            if (control != null) control.close();
        } catch (IOException ignored) {
        }
    }

    public boolean isConnected() {
        return control != null && control.isConnected() && !control.isClosed();
    }

    private String send(String cmd) throws IOException {
        writer.write(cmd + "\r\n");
        writer.flush();
        return readReply();
    }

    private String readReply() throws IOException {
        String line = reader.readLine();
        if (line == null) return null;
        StringBuilder sb = new StringBuilder(line);
        if (line.length() >= 4 && line.charAt(3) == '-') {
            String code = line.substring(0, 3);
            while (true) {
                line = reader.readLine();
                if (line == null) break;
                sb.append('\n').append(line);
                if (line.length() >= 4 && line.startsWith(code) && line.charAt(3) == ' ') break;
            }
        }
        return sb.toString();
    }

    private Socket openData() throws IOException {
        String r = send("PASV");
        if (r == null || !r.startsWith("227")) throw new IOException("被动模式失败: " + safe(r));
        int open = r.indexOf('(');
        int close = r.lastIndexOf(')');
        if (open < 0 || close <= open) throw new IOException("被动模式地址解析失败");
        String[] nums = r.substring(open + 1, close).split(",");
        if (nums.length < 6) throw new IOException("被动模式地址解析失败");
        String host = nums[0] + "." + nums[1] + "." + nums[2] + "." + nums[3];
        int port = Integer.parseInt(nums[4].trim()) * 256 + Integer.parseInt(nums[5].trim());
        Socket data = new Socket();
        data.connect(new InetSocketAddress(host, port), 15000);
        data.setSoTimeout(20000);
        return data;
    }

    private void closeData(Socket s) {
        try {
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("\r", "").replace("\n", "");
    }

    static RemoteFile parseListLine(String line) {
        if (line == null || line.trim().isEmpty()) return null;
        String t = line.trim();
        boolean winDir = t.contains("<DIR>");
        String[] parts = t.split("\\s+");
        boolean isDir = t.startsWith("d") || winDir;
        long size = 0;
        int nameIdx;
        if (!winDir && parts.length >= 9 && (t.startsWith("-") || t.startsWith("d"))) {
            // Unix: -rw-r--r-- 1 owner group 4096 Oct 23 12:00 name
            size = parseLongSafe(parts[4]);
            nameIdx = 8;
        } else if (winDir) {
            nameIdx = 3;
        } else {
            nameIdx = parts.length - 1;
        }
        if (nameIdx >= parts.length) nameIdx = parts.length - 1;
        StringBuilder name = new StringBuilder();
        for (int i = nameIdx; i < parts.length; i++) {
            if (name.length() > 0) name.append(' ');
            name.append(parts[i]);
        }
        String nm = name.toString();
        if (nm.equals(".") || nm.equals("..")) return null;
        return new RemoteFile(nm, isDir, size, "", "/");
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}

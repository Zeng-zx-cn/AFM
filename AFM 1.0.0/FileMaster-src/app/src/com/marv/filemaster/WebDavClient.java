package com.marv.filemaster;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 精简 WebDAV 客户端：基于 HttpURLConnection + Basic Auth 实现 PROPFIND/MKCOL/DELETE/GET/PUT。
 * 兼容坚果云、Nextcloud 等标准 WebDAV 服务。
 */
public final class WebDavClient {
    private final String baseUrl;
    private final String user;
    private final String pass;

    public WebDavClient(String baseUrl, String user, String pass) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.user = user;
        this.pass = pass;
    }

    public List<RemoteFile> list(String path) throws IOException {
        String url = join(baseUrl, path);
        HttpURLConnection conn = open("PROPFIND", url);
        conn.setRequestProperty("Depth", "1");
        conn.setRequestProperty("Content-Type", "application/xml");
        conn.setDoOutput(true);
        OutputStream os = conn.getOutputStream();
        os.write("<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>".getBytes("UTF-8"));
        os.close();
        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String xml = readAll(in);
        conn.disconnect();
        if (code == 404) return new ArrayList<RemoteFile>();
        if (code < 200 || code >= 300) throw new IOException("列目录失败 HTTP " + code + ": " + truncate(xml, 160));
        return parseMultiStatus(xml, baseUrl);
    }

    public void mkdir(String path) throws IOException {
        HttpURLConnection conn = open("MKCOL", join(baseUrl, path));
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code < 200 || code >= 300) throw new IOException("新建目录失败 HTTP " + code);
    }

    public void delete(String path) throws IOException {
        HttpURLConnection conn = open("DELETE", join(baseUrl, path));
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code < 200 || code >= 300) throw new IOException("删除失败 HTTP " + code);
    }

    public void download(String path, File local) throws IOException {
        HttpURLConnection conn = open("GET", join(baseUrl, path));
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new IOException("下载失败 HTTP " + code);
        }
        InputStream in = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(local);
        byte[] buf = new byte[65536];
        int n;
        try {
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } finally {
            fos.close();
            in.close();
            conn.disconnect();
        }
    }

    public void upload(File local, String path) throws IOException {
        HttpURLConnection conn = open("PUT", join(baseUrl, path));
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(local.length());
        OutputStream out = conn.getOutputStream();
        FileInputStream fis = new FileInputStream(local);
        byte[] buf = new byte[65536];
        int n;
        try {
            while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            fis.close();
            out.close();
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code < 200 || code >= 300) throw new IOException("上传失败 HTTP " + code);
    }

    private HttpURLConnection open(String method, String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Authorization", "Basic " + base64(user + ":" + pass));
        return conn;
    }

    private static String join(String base, String path) {
        if (path == null || path.isEmpty() || path.equals("/")) return base;
        String p = path.startsWith("/") ? path.substring(1) : path;
        return base + encodePath(p);
    }

    private static String encodePath(String p) {
        try {
            return java.net.URLEncoder.encode(p, "UTF-8").replace("+", "%20").replace("%2F", "/");
        } catch (Exception e) {
            return p;
        }
    }

    static List<RemoteFile> parseMultiStatus(String xml, String base) {
        List<RemoteFile> out = new ArrayList<>();
        String marker = "<d:response>";
        int idx = 0;
        while (true) {
            int s = xml.indexOf(marker, idx);
            if (s < 0) break;
            int e = xml.indexOf("</d:response>", s);
            if (e < 0) break;
            String block = xml.substring(s, e);
            idx = e + 14;
            String href = extract(block, "<d:href>", "</d:href>");
            if (href == null) href = extract(block, "<href>", "</href>");
            if (href == null) continue;
            String decoded = decodeHref(href);
            String rel = decoded;
            try {
                URI baseUri = new URI(base);
                URI full = new URI(decoded);
                rel = baseUri.relativize(full).toString();
            } catch (Exception ignored) {
            }
            if (rel == null || rel.isEmpty()) rel = decoded;
            boolean self = rel.equals("") || rel.equals("/");
            String name;
            if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
            int slash = rel.lastIndexOf('/');
            name = slash >= 0 ? rel.substring(slash + 1) : rel;
            if (name.isEmpty() || self) continue;
            boolean isDir = block.contains("<d:collection") || block.contains("<collection");
            long size = parseLongSafe(extract(block, "<d:getcontentlength>", "</d:getcontentlength>"));
            String time = extract(block, "<d:getlastmodified>", "</d:getlastmodified>");
            out.add(new RemoteFile(name, isDir, size, time == null ? "" : time, rel));
        }
        return out;
    }

    private static String decodeHref(String href) {
        try {
            return URLDecoder.decode(href, "UTF-8");
        } catch (Exception e) {
            return href;
        }
    }

    private static String extract(String s, String open, String close) {
        int a = s.indexOf(open);
        if (a < 0) return null;
        a += open.length();
        int b = s.indexOf(close, a);
        if (b < 0) return null;
        return s.substring(a, b).trim();
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            in.close();
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static long parseLongSafe(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static String base64(String s) {
        return android.util.Base64.encodeToString(s.getBytes(), android.util.Base64.NO_WRAP);
    }
}

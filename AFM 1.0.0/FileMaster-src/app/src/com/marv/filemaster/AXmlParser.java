package com.marv.filemaster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Android 二进制 XML（AXML）解析器：将 APK 内的 AndroidManifest.xml、res/*.xml
 * 等二进制资源解析为可读文本。纯手写解析，无第三方依赖。
 */
public final class AXmlParser {

    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_RESOURCE_MAP = 0x0180;
    private static final int CHUNK_START_NAMESPACE = 0x0100;
    private static final int CHUNK_END_NAMESPACE = 0x0101;
    private static final int CHUNK_START_ELEMENT = 0x0102;
    private static final int CHUNK_END_ELEMENT = 0x0103;
    private static final int CHUNK_CDATA = 0x0104;

    private final byte[] data;
    private int pos;
    private final List<String> strings = new ArrayList<String>();
    private final StringBuilder out = new StringBuilder(8192);
    private int indent;
    private Map<Integer, String> attrMap;

    private AXmlParser(byte[] data, Map<Integer, String> attrMap) {
        this.data = data;
        this.attrMap = attrMap;
    }

    /** 解析二进制 XML 为可读文本 */
    public static String parse(byte[] data) throws IOException {
        return parse(data, null);
    }

    /** 解析二进制 XML 为可读文本，可携带 android 属性 ID 映射表 */
    public static String parse(byte[] data, Map<Integer, String> attrMap) throws IOException {
        AXmlParser p = new AXmlParser(data, attrMap);
        p.run();
        return p.out.toString();
    }

    /** 从 ZIP（APK）中读取指定条目并解析 */
    public static String parseZipEntry(ZipFile zip, ZipEntry entry) throws IOException {
        return parseZipEntry(zip, entry, null);
    }

    /** 从 ZIP（APK）中读取指定条目并解析，可携带 android 属性 ID 映射表 */
    public static String parseZipEntry(ZipFile zip, ZipEntry entry, Map<Integer, String> attrMap) throws IOException {
        InputStream in = zip.getInputStream(entry);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return parse(bos.toByteArray(), attrMap);
    }

    private void run() throws IOException {
        if (data.length < 8) throw new IOException("数据过短");
        int fileType = readU16();
        int headerSize = readU16();
        readU32(); // chunkSize
        if (fileType != 0x0003) {
            throw new IOException("不是 Android 二进制 XML（type=0x" + Integer.toHexString(fileType) + "）");
        }
        pos = headerSize;
        while (pos < data.length) {
            if (pos + 8 > data.length) break;
            int chunkStart = pos;
            int type = readU16();
            int hsize = readU16();
            int csize = readU32();
            int end = chunkStart + (csize > 0 ? csize : hsize);
            if (end > data.length) end = data.length;
            switch (type) {
                case CHUNK_STRING_POOL:
                    readStringPool(chunkStart, end);
                    break;
                case CHUNK_RESOURCE_MAP:
                case CHUNK_START_NAMESPACE:
                case CHUNK_END_NAMESPACE:
                    pos = end;
                    break;
                case CHUNK_START_ELEMENT:
                    readStartElement(end);
                    break;
                case CHUNK_END_ELEMENT:
                    readEndElement(end);
                    break;
                case CHUNK_CDATA:
                    readCdata(end);
                    break;
                default:
                    pos = end;
                    break;
            }
        }
    }

    private void readStringPool(int chunkStart, int end) throws IOException {
        int stringCount = readU32();
        int styleCount = readU32();
        int flags = readU32();
        int stringsStart = readU32();
        int stylesStart = readU32();
        boolean utf8 = (flags & 0x00000100) != 0;
        int dataStart = chunkStart + stringsStart;
        int[] offsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++) offsets[i] = readU32();
        for (int i = 0; i < styleCount; i++) readU32();
        for (int i = 0; i < stringCount; i++) {
            int off = dataStart + offsets[i];
            if (off < 0 || off >= data.length) {
                strings.add("");
                continue;
            }
            if (utf8) {
                int[] len = readUtf8Len(off);
                int byteLen = len[1];
                int strStart = len[2];
                if (byteLen > 0 && strStart >= 0 && strStart + byteLen <= data.length) {
                    strings.add(new String(data, strStart, byteLen, Charset.forName("UTF-8")));
                } else {
                    strings.add("");
                }
            } else {
                int charLen = readU16(off);
                if (charLen < 0) charLen = 0;
                StringBuilder sb = new StringBuilder(charLen);
                int p = off + 2;
                for (int c = 0; c < charLen; c++) {
                    if (p + 2 > data.length) break;
                    sb.append((char) readU16(p));
                    p += 2;
                }
                strings.add(sb.toString());
            }
        }
        pos = end;
    }

    /** UTF-8 字符串头：返回 [charLen, byteLen, dataStart] */
    private int[] readUtf8Len(int start) {
        int p = start;
        int first = data[p] & 0xFF;
        p++;
        int charLen;
        if ((first & 0x80) != 0) {
            if (p >= data.length) return new int[]{0, 0, start};
            charLen = ((first & 0x7F) << 8) | (data[p] & 0xFF);
            p++;
        } else {
            charLen = first;
        }
        first = data[p] & 0xFF;
        p++;
        int byteLen;
        if ((first & 0x80) != 0) {
            if (p >= data.length) return new int[]{0, 0, start};
            byteLen = ((first & 0x7F) << 8) | (data[p] & 0xFF);
            p++;
        } else {
            byteLen = first;
        }
        if (byteLen == 0) p++; // 空串末尾 0
        return new int[]{charLen, byteLen, p};
    }

    private void readStartElement(int end) throws IOException {
        readU32(); // line
        readU32(); // comment
        int ns = readU32();
        int name = readU32();
        int attrStart = readU16();
        int attrSize = readU16();
        int attrCount = readU16();
        readU16(); // idIndex
        readU16(); // classIndex
        readU16(); // styleIndex
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indent; i++) sb.append("  ");
        String prefix = nsToPrefix(getString(ns));
        sb.append('<');
        if (prefix != null && prefix.length() > 0) sb.append(prefix).append(':');
        sb.append(getString(name));
        for (int i = 0; i < attrCount; i++) {
            int aNs = readU32();
            int aName = readU32();
            int aRaw = readU32();
            readU16(); // typedValue.size
            readU8();  // typedValue.res0
            int tType = readU8();
            int tData = readU32();
            String value = formatValue(tType, tData, aRaw);
            sb.append('\n');
            for (int j = 0; j <= indent; j++) sb.append("  ");
            sb.append(' ');
            String aPrefix = nsToPrefix(getString(aNs));
            if (aPrefix != null && aPrefix.length() > 0) sb.append(aPrefix).append(':');
            sb.append(getString(aName)).append('=').append('"').append(escapeXml(value)).append('"');
        }
        sb.append('>');
        out.append(sb);
        indent++;
        pos = end;
    }

    private void readEndElement(int end) throws IOException {
        readU32(); // line
        readU32(); // comment
        int ns = readU32();
        int name = readU32();
        indent--;
        if (indent < 0) indent = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indent; i++) sb.append("  ");
        String prefix = nsToPrefix(getString(ns));
        sb.append("</");
        if (prefix != null && prefix.length() > 0) sb.append(prefix).append(':');
        sb.append(getString(name)).append('>');
        out.append(sb);
        pos = end;
    }

    private void readCdata(int end) throws IOException {
        readU32(); // line
        readU32(); // comment
        readU32(); // ns
        readU32(); // name
        readU16(); // typedValue.size
        readU8();  // res0
        int tType = readU8();
        int tData = readU32();
        String text;
        if (tType == 0x03) {
            text = getString(tData);
        } else {
            text = formatValue(tType, tData, -1);
        }
        if (text != null && text.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < indent; i++) sb.append("  ");
            sb.append(escapeXml(text));
            out.append(sb);
        }
        pos = end;
    }

    private String getString(int idx) {
        if (idx >= 0 && idx < strings.size()) return strings.get(idx);
        return "@str" + idx;
    }

    /** 属性名解析：资源 ID 形式（0x01xxxxxx）优先查映射表，否则还原为可读名 */
    private String resolveAttrName(int aName) {
        if (aName >= 0x01000000) {
            String mapped = attrMap != null ? attrMap.get(aName) : null;
            return mapped != null ? mapped : ("@0x" + Integer.toHexString(aName));
        }
        return getString(aName);
    }

    private String nsToPrefix(String uri) {
        if (uri == null || uri.length() == 0) return null;
        int idx = uri.indexOf("/apk/res/");
        if (idx >= 0) {
            String tail = uri.substring(idx + "/apk/res/".length());
            if (tail.length() > 0) return tail;
            return null;
        }
        return null;
    }

    private String formatValue(int type, int data, int rawIdx) {
        switch (type) {
            case 0x00:
                return "";
            case 0x01:
                return "@0x" + Integer.toHexString(data);
            case 0x02:
                return "?0x" + Integer.toHexString(data);
            case 0x03:
                return getString(rawIdx);
            case 0x04:
                return String.valueOf(Float.intBitsToFloat(data));
            case 0x05:
                return formatDimension(data);
            case 0x06:
                return String.valueOf(data);
            case 0x10:
                return String.valueOf(data);
            case 0x11:
                return "0x" + Integer.toHexString(data);
            case 0x12:
                return data != 0 ? "true" : "false";
            case 0x1c:
                return formatColor(data);
            case 0x1d:
                return formatColor(data | 0xFF000000);
            case 0x1e:
                return formatColor4(data);
            case 0x1f:
                return formatColor4(data | 0xF0000000);
            default:
                return String.valueOf(data);
        }
    }

    private String formatDimension(int complex) {
        int unit = complex & 0x0F;
        int mantissa = (complex >> 8) & 0xFFFFFF;
        int radix = (complex >> 4) & 0x03;
        double v = mantissa * Math.pow(10, radix);
        String num;
        if (Math.abs(v - Math.round(v)) < 0.001) {
            num = String.valueOf((long) Math.round(v));
        } else {
            num = String.format("%.2f", v);
        }
        String[] units = {"px", "dp", "sp", "pt", "in", "mm"};
        return num + (unit >= 0 && unit < units.length ? units[unit] : "");
    }

    private String formatColor(int argb) {
        return String.format("#%08X", argb);
    }

    private String formatColor4(int argb4) {
        int a = (argb4 >> 28) & 0x0F, r = (argb4 >> 24) & 0x0F, g = (argb4 >> 20) & 0x0F, b = (argb4 >> 16) & 0x0F;
        return String.format("#%X%X%X%X%X%X%X%X", a, a, r, r, g, g, b, b);
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    private int readU16() throws IOException {
        if (pos + 2 > data.length) throw new IOException("越界");
        int v = ((data[pos] & 0xFF)) | ((data[pos + 1] & 0xFF) << 8);
        pos += 2;
        return v;
    }

    private int readU16(int at) {
        if (at < 0 || at + 2 > data.length) return 0;
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
    }

    private int readU8() throws IOException {
        if (pos + 1 > data.length) throw new IOException("越界");
        return data[pos++] & 0xFF;
    }

    private int readU32() throws IOException {
        if (pos + 4 > data.length) throw new IOException("越界");
        int v = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8)
                | ((data[pos + 2] & 0xFF) << 16) | ((data[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }
}

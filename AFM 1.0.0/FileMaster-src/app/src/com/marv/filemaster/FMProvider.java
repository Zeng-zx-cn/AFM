package com.marv.filemaster;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 轻量 FileProvider：将任意本地文件以 content:// 暴露给系统应用打开。
 * URI 格式：content://com.marv.filemaster.fmprovider/ + 绝对路径
 */
public class FMProvider extends ContentProvider {

    public static final String AUTHORITY = "com.marv.filemaster.fmprovider";

    public static Uri getUriForFile(Context context, File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + file.getAbsolutePath());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null) throw new FileNotFoundException();
        String absolute = path.startsWith("/") ? path : "/" + path;
        File file = new File(absolute);
        if (!file.exists()) throw new FileNotFoundException(absolute);
        int flags = ParcelFileDescriptor.MODE_READ_ONLY;
        if (mode != null && (mode.contains("w") || mode.contains("a"))) {
            flags = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
        }
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return getTypeInternal(uri);
    }

    private String getTypeInternal(Uri uri) {
        String path = uri.getPath();
        if (path == null) return "*/*";
        int idx = path.lastIndexOf('.');
        String ext = idx >= 0 ? path.substring(idx + 1) : "";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
        return mime != null ? mime : "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        ParcelFileDescriptor pfd = openFile(uri, mode);
        return pfd != null ? new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH) : null;
    }
}

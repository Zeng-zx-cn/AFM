package com.marv.filemaster;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 书签存储：基于 SharedPreferences 保存收藏的路径列表。
 */
public final class BookmarkStore {
    private static final String PREFS = "bookmarks";
    private static final String KEY = "paths";
    private final SharedPreferences prefs;

    public BookmarkStore(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<String> getAll() {
        String raw = prefs.getString(KEY, "");
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        String[] parts = raw.split("\u0001");
        for (String p : parts) {
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    public boolean has(String path) {
        return getAll().contains(path);
    }

    public boolean add(String path) {
        List<String> list = getAll();
        if (list.contains(path)) return false;
        list.add(path);
        save(list);
        return true;
    }

    public boolean remove(String path) {
        List<String> list = getAll();
        if (!list.remove(path)) return false;
        save(list);
        return true;
    }

    public void clear() {
        prefs.edit().remove(KEY).apply();
    }

    private void save(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String p : list) {
            if (sb.length() > 0) sb.append('\u0001');
            sb.append(p);
        }
        prefs.edit().putString(KEY, sb.toString()).apply();
    }
}

package com.marv.filemaster;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * 主题管理器：亮色/暗色切换，状态持久化到 SharedPreferences。
 * 提供统一的颜色取值入口，供主界面、列表、编辑器按主题取色。
 */
public final class ThemeManager {

    private static final String PREF = "fm_theme";
    private static final String KEY_DARK = "dark";

    private ThemeManager() {}

    public static boolean isDark(Context c) {
        return getPrefs(c).getBoolean(KEY_DARK, false);
    }

    public static void setDark(Context c, boolean dark) {
        getPrefs(c).edit().putBoolean(KEY_DARK, dark).apply();
    }

    public static void toggle(Context c) {
        setDark(c, !isDark(c));
    }

    private static SharedPreferences getPrefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 按主题返回颜色 */
    public static int color(Context c, int light, int dark) {
        return isDark(c) ? dark : light;
    }

    // 暗色语义色
    public static final int DARK_BG = Color.parseColor("#121212");
    public static final int DARK_PANEL = Color.parseColor("#1E1E1E");
    public static final int DARK_BAR = Color.parseColor("#1F2937");
    public static final int DARK_PATH = Color.parseColor("#2D3748");
    public static final int DARK_DIVIDER = Color.parseColor("#374151");
    public static final int DARK_TEXT_MAIN = Color.parseColor("#E5E7EB");
    public static final int DARK_TEXT_SUB = Color.parseColor("#9CA3AF");
    public static final int DARK_SELECTED = Color.parseColor("#1E3A5F");
}

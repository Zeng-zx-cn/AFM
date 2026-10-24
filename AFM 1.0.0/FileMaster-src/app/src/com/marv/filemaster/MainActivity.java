package com.marv.filemaster;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：类 MT 管理器的双窗口文件管理器。
 * 支持浏览、多选、复制/剪切/粘贴、删除、重命名、新建、压缩/解压、搜索、文本编辑。
 */
public class MainActivity extends Activity {

    private FilePanel leftPanel, rightPanel;
    private FilePanel activePanel;
    private TextView statusView;
    private Button btnSelectAll, btnMulti, btnInvert, btnSort, btnRoot, btnTheme;
    private boolean permissionGranted = false;

    private static class Clipboard {
        boolean isMove;
        boolean fromRoot;
        List<File> files = new ArrayList<>();
    }

    private Clipboard clipboard = new Clipboard();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.txt_status);
        btnSelectAll = findViewById(R.id.btn_select_all);
        btnMulti = findViewById(R.id.btn_multi);
        btnInvert = findViewById(R.id.btn_invert);
        btnSort = findViewById(R.id.btn_sort);
        btnRoot = findViewById(R.id.btn_root);
        btnTheme = findViewById(R.id.btn_theme);

        File startDir = Environment.getExternalStorageDirectory();
        if (startDir == null) startDir = new File("/");

        leftPanel = new FilePanel(findViewById(R.id.panel_left_list), findViewById(R.id.panel_left_path), startDir, panelListener);
        rightPanel = new FilePanel(findViewById(R.id.panel_right_list), findViewById(R.id.panel_right_path), startDir, panelListener);
        activePanel = leftPanel;

        // 面板激活：点击列表时切换焦点面板
        findViewById(R.id.panel_left_list).setOnTouchListener((v, event) -> {
            activePanel = leftPanel;
            return false;
        });
        findViewById(R.id.panel_right_list).setOnTouchListener((v, event) -> {
            activePanel = rightPanel;
            return false;
        });

        initToolbar();
        initActions();
        updateStatus();
        applyTheme();

        checkPermissionAndRun();
    }

    private FilePanel.Listener panelListener = new FilePanel.Listener() {
        @Override
        public void onPathChanged(File dir) {
            activePanel = getPanelByDir(dir);
            TextView topPath = findViewById(R.id.txt_path);
            if (topPath != null) topPath.setText(dir.getAbsolutePath());
        }

        @Override
        public void onOpenFile(File f) {
            openFile(f);
        }

        @Override
        public void onSelectionChanged(int count) {
            updateStatus();
        }
    };

    private FilePanel getPanelByDir(File dir) {
        if (leftPanel.getCurrentDir() != null && leftPanel.getCurrentDir().equals(dir)) return leftPanel;
        return rightPanel;
    }

    private void initToolbar() {
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (activePanel != null) activePanel.goUp();
        });
        findViewById(R.id.btn_search).setOnClickListener(v -> showSearchDialog());
    }

    private void initActions() {
        findViewById(R.id.btn_new_folder).setOnClickListener(v -> showInputDialog("新建文件夹", "", name -> {
            boolean ok;
            if (activePanel.isRootMode() && !RootShell.directlyReadable(activePanel.getCurrentDir())) {
                ok = RootShell.mkdir(activePanel.getCurrentDir(), name);
            } else {
                ok = FileUtil.newFolder(activePanel.getCurrentDir(), name);
            }
            if (ok) {
                toast("已创建");
                refreshActive();
            } else {
                toast("创建失败");
            }
        }));

        findViewById(R.id.btn_copy).setOnClickListener(v -> prepareClipboard(false));
        findViewById(R.id.btn_cut).setOnClickListener(v -> prepareClipboard(true));

        findViewById(R.id.btn_paste).setOnClickListener(v -> {
            if (clipboard.files.isEmpty()) {
                toast("剪贴板为空");
                return;
            }
            performPaste();
        });

        findViewById(R.id.btn_delete).setOnClickListener(v -> {
            final List<File> files = activePanel.getSelectedFiles();
            if (files.isEmpty()) {
                toast("请先选择文件");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("删除确认")
                    .setMessage("确定删除选中的 " + files.size() + " 个文件/文件夹吗？此操作不可恢复。")
                    .setPositiveButton("删除", (d, w) -> performDelete(files))
                    .setNegativeButton("取消", null)
                    .show();
        });

        findViewById(R.id.btn_rename).setOnClickListener(v -> {
            List<File> files = activePanel.getSelectedFiles();
            if (files.size() != 1) {
                toast("请只选择一个文件进行重命名");
                return;
            }
            File f = files.get(0);
            showInputDialog("重命名", f.getName(), name -> {
                boolean ok;
                if (activePanel.isRootMode() && !RootShell.directlyReadable(f.getParentFile())) {
                    ok = RootShell.rename(f, name);
                } else {
                    ok = FileUtil.rename(f, name);
                }
                if (ok) {
                    toast("已重命名");
                    refreshActive();
                } else {
                    toast("重命名失败（可能已存在同名文件）");
                }
            });
        });

        findViewById(R.id.btn_zip).setOnClickListener(v -> {
            List<File> files = activePanel.getSelectedFiles();
            if (files.isEmpty()) {
                toast("请先选择文件");
                return;
            }
            showInputDialog("压缩为 ZIP", "archive.zip", name -> {
                String zipName = name.trim();
                if (!zipName.endsWith(".zip")) zipName = zipName + ".zip";
                File zipFile = new File(activePanel.getCurrentDir(), zipName);
                performZip(files, zipFile);
            });
        });

        findViewById(R.id.btn_unzip).setOnClickListener(v -> {
            List<File> files = activePanel.getSelectedFiles();
            if (files.size() != 1 || !FileUtil.isZipFile(files.get(0))) {
                toast("请只选择一个 ZIP 文件");
                return;
            }
            File zipFile = files.get(0);
            String baseName = zipFile.getName();
            if (baseName.toLowerCase().endsWith(".zip")) {
                baseName = baseName.substring(0, baseName.length() - 4);
            }
            File dest = new File(zipFile.getParentFile(), baseName);
            performUnzip(zipFile, dest);
        });

        btnSelectAll.setOnClickListener(v -> {
            if (activePanel.isSelectionMode()) {
                activePanel.exitSelectionMode();
                btnSelectAll.setText(R.string.action_select_all);
            } else {
                activePanel.selectAll();
                btnSelectAll.setText(R.string.action_clear);
            }
            updateStatus();
        });

        findViewById(R.id.btn_refresh).setOnClickListener(v -> {
            refreshActive();
            toast("已刷新");
        });

        // 多选批量操作：一键进入/退出多选模式
        btnMulti.setOnClickListener(v -> {
            if (activePanel.isSelectionMode()) {
                activePanel.exitSelectionMode();
            } else {
                activePanel.enterSelectionMode();
            }
            updateStatus();
        });

        // 反选
        btnInvert.setOnClickListener(v -> {
            if (!activePanel.isSelectionMode()) {
                activePanel.enterSelectionMode();
            }
            activePanel.getAdapter().invertSelection();
            updateStatus();
        });

        // 排序循环：名称 → 大小 → 时间 → 类型
        btnSort.setOnClickListener(v -> {
            int next = (activePanel.getSortMode() + 1) % 4;
            activePanel.setSortMode(next);
            activePanel.refresh();
            updateSortLabel();
        });

        // Root 模式开关
        btnRoot.setOnClickListener(v -> toggleRootMode());

        // 主题切换
        btnTheme.setOnClickListener(v -> {
            ThemeManager.toggle(this);
            applyTheme();
            refreshAll();
            updateStatus();
        });

        // 反编译 XML：扫描当前目录 APK 并打开查看器
        findViewById(R.id.btn_decompile).setOnClickListener(v -> showDecompileDialog());
    }

    private void showDecompileDialog() {
        File dir = activePanel.getCurrentDir();
        File[] files = dir.listFiles();
        if (files == null) {
            toast(getString(R.string.decompile_unreadable));
            return;
        }
        final List<File> apks = new ArrayList<File>();
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".apk")) {
                apks.add(f);
            }
        }
        if (apks.isEmpty()) {
            toast(getString(R.string.decompile_no_apk));
            return;
        }
        String[] names = new String[apks.size()];
        for (int i = 0; i < apks.size(); i++) names[i] = apks.get(i).getName();
        new AlertDialog.Builder(this)
                .setTitle(R.string.decompile_title)
                .setItems(names, (d, which) -> {
                    Intent intent = new Intent(this, XmlViewerActivity.class);
                    intent.putExtra("apk", apks.get(which).getAbsolutePath());
                    startActivity(intent);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void toggleRootMode() {
        if (!RootShell.isRootAvailable()) {
            toast(getString(R.string.root_unavailable));
            return;
        }
        boolean on = !activePanel.isRootMode();
        leftPanel.setRootMode(on);
        rightPanel.setRootMode(on);
        btnRoot.setText(on ? R.string.action_root_on : R.string.action_root);
        toast(getString(on ? R.string.root_mode_on : R.string.root_mode_off));
        // Root 模式开启后导航到根目录，便于访问系统目录
        if (on && !activePanel.getCurrentDir().getAbsolutePath().equals("/")) {
            activePanel.navigate(new File("/"));
        } else {
            refreshAll();
        }
    }

    private void updateSortLabel() {
        int m = activePanel.getSortMode();
        int res = m == FileUtil.SORT_SIZE ? R.string.sort_size
                : m == FileUtil.SORT_TIME ? R.string.sort_time
                : m == FileUtil.SORT_TYPE ? R.string.sort_type
                : R.string.sort_name;
        btnSort.setText(res);
    }

    private void applyTheme() {
        boolean dark = ThemeManager.isDark(this);
        int bar = dark ? ThemeManager.DARK_BAR : getResources().getColor(R.color.primary);
        int statusBg = dark ? ThemeManager.DARK_BG : getResources().getColor(R.color.primary_dark);
        int toolBg = dark ? ThemeManager.DARK_BAR : getResources().getColor(R.color.tool_bg);
        int panelBg = dark ? ThemeManager.DARK_PANEL : getResources().getColor(R.color.panel_bg);
        int pathBg = dark ? ThemeManager.DARK_PATH : getResources().getColor(R.color.panel_path_bg);
        int divider = dark ? ThemeManager.DARK_DIVIDER : getResources().getColor(R.color.divider_v);

        findViewById(R.id.toolbar_top).setBackgroundColor(bar);
        findViewById(R.id.txt_status).setBackgroundColor(statusBg);
        findViewById(R.id.bottom_bar).setBackgroundColor(toolBg);
        findViewById(R.id.panel_left).setBackgroundColor(panelBg);
        findViewById(R.id.panel_right).setBackgroundColor(panelBg);
        findViewById(R.id.panel_left_path).setBackgroundColor(pathBg);
        findViewById(R.id.panel_right_path).setBackgroundColor(pathBg);
        findViewById(R.id.divider_mid).setBackgroundColor(divider);

        int listDivider = dark ? ThemeManager.DARK_DIVIDER : getResources().getColor(R.color.divider);
        leftPanel.getListView().setDivider(new ColorDrawable(listDivider));
        rightPanel.getListView().setDivider(new ColorDrawable(listDivider));

        // 底部按钮文字颜色
        int btnColor = dark ? Color.WHITE : Color.parseColor("#212121");
        int[] ids = {R.id.btn_new_folder, R.id.btn_copy, R.id.btn_cut, R.id.btn_paste,
                R.id.btn_delete, R.id.btn_rename, R.id.btn_zip, R.id.btn_unzip,
                R.id.btn_select_all, R.id.btn_refresh, R.id.btn_multi, R.id.btn_invert,
                R.id.btn_sort, R.id.btn_root, R.id.btn_theme,
                R.id.btn_fav, R.id.btn_bookmark, R.id.btn_ftp, R.id.btn_cloud,
                R.id.btn_decompile};
        for (int id : ids) {
            View v = findViewById(id);
            if (v instanceof Button) ((Button) v).setTextColor(btnColor);
        }

        // 顶部图标按钮着色
        findViewById(R.id.btn_back).getBackground().setTint(dark ? Color.WHITE : Color.TRANSPARENT);
        findViewById(R.id.btn_search).getBackground().setTint(dark ? Color.WHITE : Color.TRANSPARENT);
    }

    // ---------- 剪贴板 ----------

    private void prepareClipboard(boolean isMove) {
        List<File> files = activePanel.getSelectedFiles();
        if (files.isEmpty()) {
            toast("请先选择文件");
            return;
        }
        clipboard.isMove = isMove;
        clipboard.fromRoot = activePanel.isRootMode() && !RootShell.directlyReadable(activePanel.getCurrentDir());
        clipboard.files = new ArrayList<>(files);
        toast((isMove ? "已剪切 " : "已复制 ") + files.size() + " 项，请到目标目录粘贴");
        activePanel.exitSelectionMode();
        updateStatus();
    }

    private void performPaste() {
        final File target = activePanel.getCurrentDir();
        final boolean isMove = clipboard.isMove;
        final boolean needRoot = clipboard.fromRoot
                || (activePanel.isRootMode() && !RootShell.directlyReadable(target));
        final List<File> files = new ArrayList<>(clipboard.files);
        new Thread(() -> {
            int okCount = 0;
            for (File f : files) {
                try {
                    boolean ok;
                    if (needRoot) {
                        ok = isMove ? RootShell.move(f, target) : RootShell.copy(f, target);
                    } else {
                        ok = isMove ? FileUtil.move(f, target) : FileUtil.copy(f, target);
                    }
                    if (ok) okCount++;
                } catch (IOException e) {
                    // 忽略单个失败
                }
            }
            final int ok = okCount;
            final int total = files.size();
            runOnUiThread(() -> {
                toast((isMove ? "移动" : "复制") + "完成：" + ok + "/" + total);
                clipboard.files.clear();
                refreshAll();
                updateStatus();
            });
        }).start();
    }

    private void performDelete(final List<File> files) {
        final boolean needRoot = activePanel.isRootMode()
                && !RootShell.directlyReadable(activePanel.getCurrentDir());
        new Thread(() -> {
            int ok = 0;
            for (File f : files) {
                boolean deleted = needRoot ? RootShell.delete(f) : FileUtil.deleteRecursively(f);
                if (deleted) ok++;
            }
            final int deleted = ok;
            runOnUiThread(() -> {
                toast("已删除 " + deleted + " 项");
                refreshAll();
                updateStatus();
            });
        }).start();
    }

    private void performZip(final List<File> files, final File zipFile) {
        final boolean needRoot = activePanel.isRootMode()
                && !RootShell.directlyReadable(activePanel.getCurrentDir());
        new Thread(() -> {
            final String[] result = new String[1];
            try {
                if (needRoot) {
                    // Root 目录压缩：先中转缓存，再拷回目标
                    File work = new File(getCacheDir(), "fm_work_" + System.currentTimeMillis());
                    if (!work.mkdirs()) throw new IOException("无法创建临时目录");
                    for (File f : files) {
                        if (!RootShell.copy(f, work)) throw new IOException("读取失败: " + f.getName());
                    }
                    File tmpZip = new File(work, zipFile.getName());
                    ZipUtil.zip(listCacheFiles(work), tmpZip);
                    if (!RootShell.copy(tmpZip, zipFile.getParentFile())) {
                        throw new IOException("写入失败: " + zipFile.getName());
                    }
                    FileUtil.deleteRecursively(work);
                    result[0] = "压缩完成：" + zipFile.getName();
                } else {
                    ZipUtil.zip(files, zipFile);
                    result[0] = "压缩完成：" + zipFile.getName();
                }
            } catch (IOException e) {
                result[0] = "压缩失败：" + e.getMessage();
            }
            runOnUiThread(() -> {
                toast(result[0]);
                refreshAll();
                updateStatus();
            });
        }).start();
    }

    private void performUnzip(final File zipFile, final File dest) {
        final boolean needRoot = activePanel.isRootMode()
                && !RootShell.directlyReadable(activePanel.getCurrentDir());
        new Thread(() -> {
            final String[] result = new String[1];
            try {
                if (needRoot) {
                    // Root 目录解压：先中转缓存，再拷回目标
                    File work = new File(getCacheDir(), "fm_unzip_" + System.currentTimeMillis());
                    if (!work.mkdirs()) throw new IOException("无法创建临时目录");
                    File tmpZip = new File(work, zipFile.getName());
                    if (!RootShell.copy(zipFile, work)) throw new IOException("读取失败: " + zipFile.getName());
                    File outDir = new File(work, "out");
                    if (!outDir.mkdirs()) throw new IOException("无法创建输出目录");
                    ZipUtil.unzip(tmpZip, outDir);
                    if (!dest.exists() && !dest.mkdirs()) throw new IOException("无法创建目标目录");
                    for (File f : listCacheFiles(outDir)) {
                        if (!RootShell.copy(f, dest)) throw new IOException("写入失败: " + f.getName());
                    }
                    FileUtil.deleteRecursively(work);
                    result[0] = "解压完成：" + dest.getAbsolutePath();
                } else {
                    ZipUtil.unzip(zipFile, dest);
                    result[0] = "解压完成：" + dest.getAbsolutePath();
                }
            } catch (IOException e) {
                result[0] = "解压失败：" + e.getMessage();
            }
            runOnUiThread(() -> {
                toast(result[0]);
                refreshAll();
                updateStatus();
            });
        }).start();
    }

    /** 列出缓存目录内容（root 中转后落盘的文件） */
    private List<File> listCacheFiles(File dir) {
        List<File> files = new ArrayList<>();
        File[] arr = dir.listFiles();
        if (arr == null) return files;
        for (File f : arr) files.add(f);
        return files;
    }

    // ---------- 打开文件 ----------

    private void openFile(File f) {
        if (f.isDirectory()) return;
        if (FileUtil.isTextFile(f) && f.length() < 5 * 1024 * 1024) {
            Intent intent = new Intent(this, EditorActivity.class);
            intent.putExtra("path", f.getAbsolutePath());
            startActivity(intent);
            return;
        }
        try {
            Uri uri = FMProvider.getUriForFile(this, f);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, getMimeType(f));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("没有应用可以打开该文件");
        } catch (Exception e) {
            toast("打开失败：" + e.getMessage());
        }
    }

    private String getMimeType(File f) {
        String name = f.getName().toLowerCase();
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp")) {
            return "image/*";
        }
        if (name.endsWith(".mp4") || name.endsWith(".avi") || name.endsWith(".mkv")
                || name.endsWith(".mov") || name.endsWith(".3gp")) {
            return "video/*";
        }
        if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".flac")
                || name.endsWith(".m4a")) {
            return "audio/*";
        }
        if (name.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (name.endsWith(".pdf")) return "application/pdf";
        return "*/*";
    }

    // ---------- 搜索 ----------

    private void showSearchDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入文件名关键词");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("搜索文件")
                .setView(input)
                .setPositiveButton("搜索", (d, w) -> {
                    String kw = input.getText().toString().trim();
                    if (kw.length() == 0) return;
                    performSearch(kw);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void performSearch(final String keyword) {
        final File base = activePanel.getCurrentDir();
        new Thread(() -> {
            final List<File> results = new ArrayList<>();
            FileUtil.search(base, keyword, results, 4, 0);
            runOnUiThread(() -> showSearchResults(keyword, results));
        }).start();
    }

    private void showSearchResults(String keyword, final List<File> results) {
        if (results.isEmpty()) {
            toast("未找到匹配项");
            return;
        }
        String[] names = new String[results.size()];
        for (int i = 0; i < results.size(); i++) {
            File f = results.get(i);
            names[i] = (f.isDirectory() ? "[目录] " : "[文件] ") + f.getAbsolutePath();
        }
        new AlertDialog.Builder(this)
                .setTitle("搜索 “" + keyword + "” 共 " + results.size() + " 项")
                .setItems(names, (d, which) -> {
                    File f = results.get(which);
                    if (f.isDirectory()) {
                        activePanel.navigate(f);
                    } else {
                        openFile(f);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ---------- 辅助 ----------

    private void showInputDialog(String title, String initial, final NameCallback cb) {
        final EditText input = new EditText(this);
        input.setText(initial);
        input.setSelection(initial == null ? 0 : initial.length());
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("确定", (d, w) -> cb.onName(input.getText().toString()))
                .setNegativeButton("取消", null)
                .show();
    }

    private interface NameCallback {
        void onName(String name);
    }

    private void refreshActive() {
        if (activePanel != null) activePanel.refresh();
        btnSelectAll.setText(R.string.action_select_all);
        btnMulti.setText(activePanel != null && activePanel.isSelectionMode()
                ? R.string.action_exit_multi : R.string.action_multi);
    }

    private void refreshAll() {
        leftPanel.refresh();
        rightPanel.refresh();
        btnSelectAll.setText(R.string.action_select_all);
        btnMulti.setText(R.string.action_multi);
    }

    private void updateStatus() {
        String text;
        if (activePanel != null && activePanel.isSelectionMode()) {
            text = "已选择 " + activePanel.getAdapter().getSelectedCount() + " 项";
            if (!clipboard.files.isEmpty()) {
                text += " ｜ 剪贴板: " + clipboard.files.size() + " 项 (" + (clipboard.isMove ? "剪切" : "复制") + ")";
            }
            btnSelectAll.setText(R.string.action_clear);
            btnMulti.setText(R.string.action_exit_multi);
        } else {
            text = clipboard.files.isEmpty()
                    ? "长按文件可多选，支持双窗口浏览"
                    : "剪贴板: " + clipboard.files.size() + " 项 (" + (clipboard.isMove ? "剪切" : "复制") + ")，点击粘贴";
            btnSelectAll.setText(R.string.action_select_all);
            btnMulti.setText(R.string.action_multi);
        }
        if (activePanel != null && activePanel.isRootMode()) {
            text += " ｜ Root 已开启";
        }
        statusView.setText(text);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------- 权限 ----------

    private void checkPermissionAndRun() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                permissionGranted = true;
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("需要存储权限")
                        .setMessage("文件管理器需要「所有文件访问」权限才能浏览和管理手机文件。请在弹出的设置页中开启。")
                        .setPositiveButton(R.string.goto_settings, (d, w) -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            } catch (Exception e) {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            }
                        })
                        .setNegativeButton("取消", (d, w) -> finish())
                        .setCancelable(false)
                        .show();
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                permissionGranted = true;
            } else {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 100);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean all = true;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) all = false;
        }
        permissionGranted = all;
        if (!permissionGranted) toast("未获得存储权限，部分功能不可用");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置页返回后重新检查权限并刷新
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            permissionGranted = true;
        }
        refreshAll();
    }

    @Override
    public void onBackPressed() {
        if (activePanel.isSelectionMode()) {
            activePanel.exitSelectionMode();
            updateStatus();
            return;
        }
        File root = new File("/");
        if (!activePanel.getCurrentDir().equals(root)) {
            activePanel.goUp();
            return;
        }
        super.onBackPressed();
    }
}

package com.marv.filemaster;

import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * 单个文件面板：维护当前目录、文件列表、多选状态。
 * 支持双窗口，左右面板各自持有独立实例。
 */
public class FilePanel {

    public interface Listener {
        void onPathChanged(File dir);
        void onOpenFile(File f);
        void onSelectionChanged(int count);
    }

    private final ListView listView;
    private final TextView pathView;
    private File currentDir;
    private FileAdapter adapter;
    private final Listener listener;
    private boolean rootMode = false;
    private int sortMode = FileUtil.SORT_NAME;

    public FilePanel(ListView listView, TextView pathView, File startDir, final Listener listener) {
        this.listView = listView;
        this.pathView = pathView;
        this.listener = listener;
        adapter = new FileAdapter(listView.getContext(), null);
        listView.setAdapter(adapter);
        navigate(startDir);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                File f = adapter.getItem(position);
                if (f == null) return;
                if (adapter.isSelectionMode()) {
                    adapter.toggleSelected(f);
                    notifySelection();
                } else if (f.isDirectory()) {
                    navigate(f);
                } else {
                    listener.onOpenFile(f);
                }
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                File f = adapter.getItem(position);
                if (f == null) return true;
                if (!adapter.isSelectionMode()) {
                    adapter.setSelectionMode(true);
                }
                adapter.toggleSelected(f);
                notifySelection();
                return true;
            }
        });
    }

    public void navigate(File dir) {
        if (dir == null) {
            Toast.makeText(listView.getContext(), "无法访问该目录", Toast.LENGTH_SHORT).show();
            return;
        }
        List<File> items = new java.util.ArrayList<>();
        java.util.Map<File, FileMeta> meta = new java.util.HashMap<>();
        boolean needRoot = rootMode && !RootShell.directlyReadable(dir);

        if (needRoot) {
            List<RootShell.Entry> entries = RootShell.list(dir);
            if (entries.isEmpty()) {
                // root 列表为空：可能是空目录、权限失败或普通模式仍可读，回退普通列表
                items = FileUtil.listFiles(dir);
                if (items.isEmpty() && (!dir.exists() || !dir.isDirectory())) {
                    Toast.makeText(listView.getContext(), "无法访问该目录", Toast.LENGTH_SHORT).show();
                    return;
                }
                for (File f : items) {
                    meta.put(f, new FileMeta(f.isDirectory(), f.length(), f.lastModified()));
                }
            } else {
                for (RootShell.Entry e : entries) {
                    items.add(e.file);
                    meta.put(e.file, new FileMeta(e.isDir, e.size, e.time));
                }
            }
        } else {
            if (!dir.exists() || !dir.isDirectory()) {
                Toast.makeText(listView.getContext(), "无法访问该目录", Toast.LENGTH_SHORT).show();
                return;
            }
            items = FileUtil.listFiles(dir, sortMode);
            for (File f : items) {
                meta.put(f, new FileMeta(f.isDirectory(), f.length(), f.lastModified()));
            }
        }

        currentDir = dir;
        FileUtil.sortFiles(items, sortMode, meta);
        adapter.setMeta(meta);
        adapter.clear();
        adapter.addAll(items);
        adapter.setSelectionMode(false);
        pathView.setText(dir.getAbsolutePath());
        if (listener != null) listener.onPathChanged(dir);
    }

    public void goUp() {
        File parent = currentDir.getParentFile();
        if (parent != null) navigate(parent);
    }

    public void refresh() {
        navigate(currentDir);
    }

    public ListView getListView() {
        return listView;
    }

    public boolean isRootMode() {
        return rootMode;
    }

    public void setRootMode(boolean rootMode) {
        this.rootMode = rootMode;
    }

    public int getSortMode() {
        return sortMode;
    }

    public void setSortMode(int sortMode) {
        this.sortMode = sortMode;
    }

    public File getCurrentDir() {
        return currentDir;
    }

    public List<File> getItems() {
        return adapter.getItems();
    }

    public FileAdapter getAdapter() {
        return adapter;
    }

    public boolean isSelectionMode() {
        return adapter.isSelectionMode();
    }

    public void enterSelectionMode() {
        adapter.setSelectionMode(true);
        notifySelection();
    }

    public void exitSelectionMode() {
        adapter.setSelectionMode(false);
        notifySelection();
    }

    public void selectAll() {
        adapter.selectAll(adapter.getItems());
        notifySelection();
    }

    public void notifySelection() {
        if (listener != null) listener.onSelectionChanged(adapter.getSelectedCount());
    }

    /** 获取选中文件列表（保持原顺序） */
    public java.util.List<File> getSelectedFiles() {
        java.util.List<File> result = new java.util.ArrayList<>();
        for (File f : adapter.getItems()) {
            if (adapter.isSelected(f)) result.add(f);
        }
        return result;
    }
}

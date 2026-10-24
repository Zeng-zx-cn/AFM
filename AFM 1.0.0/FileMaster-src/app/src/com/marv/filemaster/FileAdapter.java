package com.marv.filemaster;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件列表适配器：图标 + 名称 + 大小/时间 + 多选复选框。
 * 支持普通模式与 Root 模式的元数据展示（metaMap 优先）。
 */
public class FileAdapter extends ArrayAdapter<File> {

    private final LayoutInflater inflater;
    private boolean selectionMode = false;
    private final Set<File> selected = new HashSet<>();
    private final Map<File, FileMeta> metaMap = new HashMap<>();

    public FileAdapter(Context context, List<File> items) {
        super(context, 0, items != null ? items : new java.util.ArrayList<File>());
        inflater = LayoutInflater.from(context);
    }

    public void setSelectionMode(boolean mode) {
        selectionMode = mode;
        if (!mode) selected.clear();
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public boolean isSelected(File f) {
        return selected.contains(f);
    }

    public void toggleSelected(File f) {
        if (selected.contains(f)) selected.remove(f);
        else selected.add(f);
        notifyDataSetChanged();
    }

    public void selectAll(List<File> items) {
        selected.clear();
        selected.addAll(items);
        notifyDataSetChanged();
    }

    /** 反选当前列表 */
    public void invertSelection() {
        List<File> items = getItems();
        Set<File> keep = new HashSet<>(selected);
        keep.retainAll(items);
        selected.clear();
        for (File f : items) {
            if (!keep.contains(f)) selected.add(f);
        }
        notifyDataSetChanged();
    }

    public Set<File> getSelected() {
        return selected;
    }

    /** 设置当前列表元数据（Root 模式来自 ls 解析，普通模式来自 File） */
    public void setMeta(Map<File, FileMeta> meta) {
        metaMap.clear();
        if (meta != null) metaMap.putAll(meta);
    }

    public FileMeta getMeta(File f) {
        return metaMap.get(f);
    }

    /** 获取当前列表全部条目（按显示顺序） */
    public List<File> getItems() {
        List<File> items = new java.util.ArrayList<>();
        for (int i = 0; i < getCount(); i++) {
            items.add(getItem(i));
        }
        return items;
    }

    public int getSelectedCount() {
        return selected.size();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_file, parent, false);
        }
        File f = getItem(position);
        ImageView icon = convertView.findViewById(R.id.item_icon);
        TextView name = convertView.findViewById(R.id.item_name);
        TextView info = convertView.findViewById(R.id.item_info);
        CheckBox check = convertView.findViewById(R.id.item_check);

        FileMeta m = metaMap.get(f);
        boolean isDir = m != null ? m.isDir : f.isDirectory();
        long size = m != null ? m.size : f.length();
        long time = m != null ? m.time : f.lastModified();

        name.setText(f.getName());

        if (isDir) {
            icon.setImageResource(R.drawable.ic_folder);
            info.setText("文件夹 · " + FileUtil.formatTime(time));
        } else if (FileUtil.isZipFile(f)) {
            icon.setImageResource(R.drawable.ic_zip);
            info.setText(FileUtil.formatSize(size) + " · " + FileUtil.formatTime(time));
        } else {
            icon.setImageResource(R.drawable.ic_file);
            info.setText(FileUtil.formatSize(size) + " · " + FileUtil.formatTime(time));
        }

        Context ctx = getContext();
        if (selectionMode) {
            check.setVisibility(View.VISIBLE);
            check.setChecked(selected.contains(f));
            int bg = selected.contains(f)
                    ? ThemeManager.color(ctx, Color.parseColor("#E8F5E9"), ThemeManager.DARK_SELECTED)
                    : Color.TRANSPARENT;
            convertView.setBackgroundColor(bg);
        } else {
            check.setVisibility(View.GONE);
            convertView.setBackgroundColor(Color.TRANSPARENT);
        }
        name.setTextColor(ThemeManager.color(ctx, Color.parseColor("#212121"), ThemeManager.DARK_TEXT_MAIN));
        info.setTextColor(ThemeManager.color(ctx, Color.parseColor("#757575"), ThemeManager.DARK_TEXT_SUB));
        return convertView;
    }
}

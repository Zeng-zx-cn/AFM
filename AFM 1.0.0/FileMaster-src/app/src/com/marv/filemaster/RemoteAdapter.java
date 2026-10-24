package com.marv.filemaster;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 远程文件列表适配器（FTP / WebDAV 通用）。
 */
public class RemoteAdapter extends ArrayAdapter<RemoteFile> {
    private final Context ctx;
    private boolean dark = false;

    public RemoteAdapter(Context context, List<RemoteFile> items) {
        super(context, R.layout.item_file, items != null ? items : new ArrayList<RemoteFile>());
        this.ctx = context;
    }

    public void setDark(boolean d) {
        dark = d;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) v = View.inflate(ctx, R.layout.item_file, null);
        RemoteFile rf = getItem(position);
        if (rf == null) return v;
        TextView name = v.findViewById(R.id.item_name);
        TextView info = v.findViewById(R.id.item_info);
        ImageView icon = v.findViewById(R.id.item_icon);
        name.setText(rf.name);
        name.setTextColor(ThemeManager.color(ctx, 0xFF333333, ThemeManager.DARK_TEXT_MAIN));
        if (rf.isDir) {
            info.setText("文件夹" + (rf.time != null && !rf.time.isEmpty() ? "  " + rf.time : ""));
            icon.setImageResource(android.R.drawable.ic_menu_agenda);
        } else {
            info.setText(RemoteFile.formatSize(rf.size) + (rf.time != null && !rf.time.isEmpty() ? "  " + rf.time : ""));
            icon.setImageResource(android.R.drawable.ic_menu_myplaces);
        }
        info.setTextColor(ThemeManager.color(ctx, 0xFF888888, ThemeManager.DARK_TEXT_SUB));
        v.findViewById(R.id.item_check).setVisibility(View.GONE);
        return v;
    }
}

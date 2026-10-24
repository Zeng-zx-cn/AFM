package com.marv.filemaster;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * FTP 远程管理界面：连接、浏览、下载、上传、新建目录、删除。
 * 主界面选中文件后通过 extra "paths"（ArrayList<String>）进入可上传。
 */
public class FtpActivity extends Activity {

    private FtpClient client;
    private String currentPath = "/";
    private final List<RemoteFile> items = new ArrayList<>();
    private final List<String> pendingUpload = new ArrayList<>();
    private RemoteAdapter adapter;
    private TextView title, status;
    private LinearLayout connPanel;
    private EditText etHost, etPort, etUser, etPass;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ftp);
        title = findViewById(R.id.txt_title);
        status = findViewById(R.id.txt_status);
        connPanel = findViewById(R.id.conn_panel);
        etHost = findViewById(R.id.et_host);
        etPort = findViewById(R.id.et_port);
        etUser = findViewById(R.id.et_user);
        etPass = findViewById(R.id.et_pass);

        SharedPreferences sp = getSharedPreferences("ftp_cfg", MODE_PRIVATE);
        etHost.setText(sp.getString("host", ""));
        etPort.setText(sp.getString("port", "21"));
        etUser.setText(sp.getString("user", ""));
        etPass.setText(sp.getString("pass", ""));

        ListView list = findViewById(R.id.remote_list);
        adapter = new RemoteAdapter(this, items);
        adapter.setDark(ThemeManager.isDark(this));
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            RemoteFile rf = items.get(pos);
            if (rf.isDir) navigate(rf.name);
            else download(rf);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            confirmDelete(items.get(pos));
            return true;
        });
        findViewById(R.id.btn_conn).setOnClickListener(v -> toggleConn());
        findViewById(R.id.btn_connect).setOnClickListener(v -> connect());
        findViewById(R.id.btn_up).setOnClickListener(v -> goUp());
        findViewById(R.id.btn_mkdir).setOnClickListener(v -> showMkdir());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> refresh());
        findViewById(R.id.btn_upload).setOnClickListener(v -> uploadPending());
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        if (getIntent().getExtras() != null) {
            List<String> p = getIntent().getExtras().getStringArrayList("paths");
            if (p != null) pendingUpload.addAll(p);
        }
        applyTheme();
        updateStatus();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 后台执行网络任务，主线程刷新 UI */
    private interface RunJob {
        void run() throws Exception;
    }

    private void bg(final RunJob job, final Runnable done) {
        if (busy) {
            toast("操作进行中，请稍候");
            return;
        }
        busy = true;
        new Thread(() -> {
            try {
                job.run();
                runOnUiThread(() -> {
                    busy = false;
                    if (done != null) done.run();
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    toast("错误：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
                });
            }
        }).start();
    }

    private void connect() {
        final String host = etHost.getText().toString().trim();
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString();
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (Exception e) {
            toast(getString(R.string.remote_incomplete));
            return;
        }
        if (host.isEmpty()) {
            toast(getString(R.string.remote_incomplete));
            return;
        }
        final int fport = port;
        bg(() -> {
            final FtpClient c = new FtpClient();
            c.connect(host, fport, 15000);
            c.login(user, pass);
            final List<RemoteFile> l = c.list(".");
            runOnUiThread(() -> {
                client = c;
                currentPath = "/";
                items.clear();
                items.addAll(l);
                adapter.notifyDataSetChanged();
                connPanel.setVisibility(View.GONE);
                setConnButton(true);
                getSharedPreferences("ftp_cfg", MODE_PRIVATE).edit()
                        .putString("host", host)
                        .putString("port", String.valueOf(fport))
                        .putString("user", user)
                        .putString("pass", pass)
                        .apply();
                toast(getString(R.string.remote_conn_ok));
                updateStatus();
            });
        }, null);
    }

    private void toggleConn() {
        if (client != null && client.isConnected()) {
            bg(() -> {
                client.disconnect();
                runOnUiThread(() -> {
                    client = null;
                    currentPath = "/";
                    items.clear();
                    adapter.notifyDataSetChanged();
                    connPanel.setVisibility(View.VISIBLE);
                    setConnButton(false);
                    toast("FTP 已断开");
                    updateStatus();
                });
            }, null);
        } else {
            connect();
        }
    }

    private void navigate(String name) {
        bg(() -> {
            client.cwd(name);
            String p = client.pwd();
            final List<RemoteFile> l = client.list(".");
            runOnUiThread(() -> {
                currentPath = p;
                title.setText(p);
                items.clear();
                items.addAll(l);
                adapter.notifyDataSetChanged();
            });
        }, null);
    }

    private void goUp() {
        bg(() -> {
            client.cwd("..");
            String p = client.pwd();
            final List<RemoteFile> l = client.list(".");
            runOnUiThread(() -> {
                currentPath = p;
                title.setText(p);
                items.clear();
                items.addAll(l);
                adapter.notifyDataSetChanged();
            });
        }, null);
    }

    private void refresh() {
        bg(() -> {
            final List<RemoteFile> l = client.list(".");
            runOnUiThread(() -> {
                items.clear();
                items.addAll(l);
                adapter.notifyDataSetChanged();
            });
        }, null);
    }

    private void download(final RemoteFile rf) {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists()) dir.mkdirs();
        final File target = uniqueFile(dir, rf.name);
        bg(() -> {
            client.download(rf.name, target);
            runOnUiThread(() -> toast(getString(R.string.remote_downloaded) + " " + target.getAbsolutePath()));
        }, null);
    }

    private void confirmDelete(final RemoteFile rf) {
        new AlertDialog.Builder(this)
                .setTitle(rf.name)
                .setMessage(getString(R.string.remote_del_confirm))
                .setPositiveButton(getString(R.string.remote_delete), (d, w) -> bg(() -> {
                    client.delete(rf.name);
                    runOnUiThread(() -> {
                        items.remove(rf);
                        adapter.notifyDataSetChanged();
                        toast(getString(R.string.remote_ok));
                    });
                }, null))
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show();
    }

    private void showMkdir() {
        final EditText input = new EditText(this);
        input.setHint(getString(R.string.mkdir_name));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.remote_mkdir))
                .setView(input)
                .setPositiveButton(getString(R.string.dialog_ok), (d, w) -> {
                    final String n = input.getText().toString().trim();
                    if (n.isEmpty()) return;
                    bg(() -> {
                        client.mkdir(n);
                        runOnUiThread(() -> {
                            refresh();
                            toast(getString(R.string.remote_ok));
                        });
                    }, null);
                })
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show();
    }

    private void uploadPending() {
        if (pendingUpload.isEmpty()) {
            toast(getString(R.string.remote_select_first));
            return;
        }
        final List<File> files = new ArrayList<>();
        for (String p : pendingUpload) {
            File f = new File(p);
            if (f.exists() && f.isFile()) files.add(f);
        }
        if (files.isEmpty()) {
            toast(getString(R.string.remote_select_first));
            return;
        }
        bg(() -> {
            int ok = 0;
            StringBuilder err = new StringBuilder();
            for (File f : files) {
                try {
                    client.upload(f, f.getName());
                    ok++;
                } catch (Exception e) {
                    err.append(f.getName()).append(": ")
                            .append(e.getMessage() == null ? e.toString() : e.getMessage())
                            .append("\n");
                }
            }
            final int okc = ok;
            final String errs = err.toString();
            runOnUiThread(() -> {
                pendingUpload.clear();
                updateStatus();
                refresh();
                toast(okc + " / " + files.size() + " 上传成功" + (errs.isEmpty() ? "" : "\n" + errs));
            });
        }, null);
    }

    private File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        while (true) {
            f = new File(dir, base + "_" + i + ext);
            if (!f.exists()) return f;
            i++;
        }
    }

    private void setConnButton(boolean connected) {
        String t = connected ? getString(R.string.ftp_disconnect) : getString(R.string.ftp_connect);
        ((Button) findViewById(R.id.btn_conn)).setText(t);
        ((Button) findViewById(R.id.btn_connect)).setText(t);
    }

    private void updateStatus() {
        if (pendingUpload.isEmpty()) {
            status.setText(getString(R.string.tip_select));
        } else {
            status.setText(String.format(getString(R.string.pending_upload), pendingUpload.size()));
        }
    }

    private void applyTheme() {
        boolean dark = ThemeManager.isDark(this);
        findViewById(R.id.remote_root).setBackgroundColor(ThemeManager.color(this, 0xFFF5F5F5, ThemeManager.DARK_BG));
        status.setTextColor(ThemeManager.color(this, 0xFFFFFFFF, 0xFFE5E7EB));
        connPanel.setBackgroundColor(ThemeManager.color(this, 0xFFF2F2F2, ThemeManager.DARK_PATH));
        for (int id : new int[]{R.id.et_host, R.id.et_port, R.id.et_user, R.id.et_pass, R.id.et_url}) {
            View v = findViewById(id);
            if (v != null) v.setBackgroundColor(ThemeManager.color(this, 0xFFFFFFFF, ThemeManager.DARK_PANEL));
        }
    }
}

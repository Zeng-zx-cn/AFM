package com.marv.filemaster;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** APK XML 反编译查看器：列出 APK 内全部 XML 条目，点击查看明文解析结果 */
public class XmlViewerActivity extends Activity {

    private TextView txtTitle;
    private ListView xmlList;
    private final List<String> entries = new ArrayList<String>();
    private ArrayAdapter<String> adapter;
    private String apkPath;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_xmlviewer);
        txtTitle = (TextView) findViewById(R.id.txt_title);
        xmlList = (ListView) findViewById(R.id.xml_list);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, entries);
        xmlList.setAdapter(adapter);
        apkPath = getIntent().getStringExtra("apk");
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        if (apkPath == null) {
            toast("缺少 APK 路径");
            finish();
            return;
        }
        final String apkName = new File(apkPath).getName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<String> xmls = listXmlEntries(apkPath);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        txtTitle.setText(apkName + " (" + xmls.size() + " 个 XML)");
                        entries.addAll(xmls);
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        }).start();
        xmlList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int position, long id) {
                showXml(entries.get(position));
            }
        });
    }

    private List<String> listXmlEntries(String path) {
        List<String> out = new ArrayList<String>();
        try {
            ZipFile zip = new ZipFile(path);
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory() && e.getName().endsWith(".xml")) {
                    out.add(e.getName());
                }
            }
            zip.close();
        } catch (Exception ignored) {
        }
        Collections.sort(out);
        return out;
    }

    /** 从 assets 加载 android 属性 ID 映射表（0x0101xxxx -> 属性名） */
    private Map<Integer, String> loadAttrMap() {
        Map<Integer, String> map = new HashMap<Integer, String>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(getAssets().open("android_attrs.txt"), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                try {
                    int id = (int) Long.parseLong(line.substring(0, sp).replace("0x", ""), 16);
                    String name = line.substring(sp + 1).trim();
                    if (name.length() > 0) map.put(id, name);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ignored) {
                }
            }
        }
        return map;
    }

    private void showXml(final String entry) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    ZipFile zip = new ZipFile(apkPath);
                    ZipEntry e = zip.getEntry(entry);
                    if (e == null) {
                        zip.close();
                        throw new Exception("条目不存在");
                    }
                    text = AXmlParser.parseZipEntry(zip, e, loadAttrMap());
                    zip.close();
                } catch (final Exception ex) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("解析失败：" + ex.getMessage());
                        }
                    });
                    return;
                }
                final String content = text;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        showXmlDialog(entry, content);
                    }
                });
            }
        }).start();
    }

    /** 查看对话框：顶部搜索栏可快速查询属性/值，正文可切换编辑模式并保存明文 */
    private void showXmlDialog(final String entry, final String content) {
        LinearLayout root = new LinearLayout(XmlViewerActivity.this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        // 顶部：关键字输入 + 查询/编辑按钮
        LinearLayout bar = new LinearLayout(XmlViewerActivity.this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        final EditText etKeyword = new EditText(XmlViewerActivity.this);
        etKeyword.setHint("输入属性名或值关键字");
        etKeyword.setSingleLine(true);
        etKeyword.setTextSize(14f);
        etKeyword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        bar.addView(etKeyword, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final Button btnQuery = new Button(XmlViewerActivity.this);
        btnQuery.setText("查询");
        btnQuery.setTextSize(13f);
        bar.addView(btnQuery);
        final Button btnEdit = new Button(XmlViewerActivity.this);
        btnEdit.setText("编辑");
        btnEdit.setTextSize(13f);
        bar.addView(btnEdit);
        root.addView(bar);

        // 正文：等宽字体，默认只读可选中；编辑模式切换为可输入
        final EditText tv = new EditText(XmlViewerActivity.this);
        tv.setText(content);
        tv.setTextSize(11f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setGravity(Gravity.TOP | Gravity.START);
        tv.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        tv.setVerticalScrollBarEnabled(true);
        tv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        tv.setTextIsSelectable(true);
        tv.setFocusable(false);
        tv.setFocusableInTouchMode(false);
        tv.setCursorVisible(false);
        ScrollView sv = new ScrollView(XmlViewerActivity.this);
        sv.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(400)));
        root.addView(sv);

        btnQuery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String kw = etKeyword.getText().toString().trim();
                if (kw.length() == 0) {
                    toast("请输入要查询的属性名或值");
                    return;
                }
                doQuery(content, kw);
            }
        });

        btnEdit.setOnClickListener(new View.OnClickListener() {
            private boolean editing = false;

            @Override
            public void onClick(View v) {
                if (editing) {
                    tv.setFocusable(false);
                    tv.setFocusableInTouchMode(false);
                    tv.setCursorVisible(false);
                    editing = false;
                    btnEdit.setText("编辑");
                    toast("已退出编辑模式");
                } else {
                    tv.setFocusableInTouchMode(true);
                    tv.setFocusable(true);
                    tv.setCursorVisible(true);
                    tv.requestFocus();
                    editing = true;
                    btnEdit.setText("完成");
                    toast("编辑完成后点「保存」导出明文");
                }
            }
        });

        new AlertDialog.Builder(XmlViewerActivity.this)
                .setTitle(entry)
                .setView(root)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        savePlainXml(entry, tv.getText().toString());
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 快速查询：按关键字逐行匹配，列出所有命中的行（带行号） */
    private void doQuery(final String content, final String kw) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int cnt = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(kw)) {
                cnt++;
                sb.append(i + 1).append(": ").append(lines[i]).append('\n');
            }
        }
        if (cnt == 0) {
            toast("未找到包含「" + kw + "」的行");
            return;
        }
        final int matchCount = cnt;
        final String result = sb.toString();
        h.post(new Runnable() {
            @Override
            public void run() {
                TextView rv = new TextView(XmlViewerActivity.this);
                rv.setText(result);
                rv.setTextIsSelectable(true);
                rv.setTextSize(12f);
                rv.setTypeface(android.graphics.Typeface.MONOSPACE);
                int pad = dp(12);
                rv.setPadding(pad, pad, pad, pad);
                new AlertDialog.Builder(XmlViewerActivity.this)
                        .setTitle("匹配 " + matchCount + " 行")
                        .setView(rv)
                        .setPositiveButton("关闭", null)
                        .show();
            }
        });
    }

    /** 把编辑后的明文 XML 保存到 Download/FileMaster/xml 目录 */
    private void savePlainXml(String entry, String text) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "FileMaster/xml");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String name = entry.replace('/', '_');
            File out = new File(dir, name + ".txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            fos.write(text.getBytes("UTF-8"));
            fos.close();
            toast("已保存明文：\n" + out.getAbsolutePath());
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

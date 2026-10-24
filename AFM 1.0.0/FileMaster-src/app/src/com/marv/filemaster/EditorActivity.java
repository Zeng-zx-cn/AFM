package com.marv.filemaster;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 内置文本编辑器：加载并保存文本文件。
 * 普通权限读取失败时回退 Root 读写；界面跟随亮/暗主题。
 */
public class EditorActivity extends Activity {

    private File file;
    private EditText editor;
    private boolean modified = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        String path = getIntent().getStringExtra("path");
        if (path == null) {
            finish();
            return;
        }
        file = new File(path);

        TextView title = findViewById(R.id.editor_title);
        editor = findViewById(R.id.editor_text);
        Button save = findViewById(R.id.btn_save);

        applyTheme();

        title.setText(file.getName());
        try {
            editor.setText(FileUtil.readText(file));
            editor.setSelection(0);
        } catch (Exception e) {
            String rootContent = RootShell.read(file);
            if (rootContent.length() == 0) {
                Toast.makeText(this, "读取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            } else {
                editor.setText(rootContent);
                editor.setSelection(0);
            }
        }
        editor.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                modified = true;
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    FileUtil.writeText(file, editor.getText().toString());
                    modified = false;
                    Toast.makeText(EditorActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    if (RootShell.write(file, editor.getText().toString())) {
                        modified = false;
                        Toast.makeText(EditorActivity.this, "已保存(Root)", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(EditorActivity.this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            }
        });
    }

    private void applyTheme() {
        boolean dark = ThemeManager.isDark(this);
        int bar = dark ? ThemeManager.DARK_BAR : getResources().getColor(R.color.primary);
        int bg = dark ? ThemeManager.DARK_PANEL : getResources().getColor(R.color.panel_bg);
        int text = dark ? ThemeManager.DARK_TEXT_MAIN : Color.parseColor("#212121");

        findViewById(R.id.editor_toolbar).setBackgroundColor(bar);
        findViewById(R.id.editor_text).setBackgroundColor(bg);
        editor.setTextColor(text);
        if (editor != null) {
            editor.setHintTextColor(dark ? ThemeManager.DARK_TEXT_SUB : Color.parseColor("#757575"));
        }
    }

    @Override
    public void onBackPressed() {
        if (modified) {
            // 用户未点保存，直接返回（简化处理）
        }
        super.onBackPressed();
    }
}

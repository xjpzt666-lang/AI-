package com.aihellotalk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

public class SettingsActivity extends Activity {

    private EditText etKey, etUrl, etModel;
    private EditText etPromptZH, etPromptEN, etPromptRU, etPromptUK;
    private Button btnFetch, btnSave, btnTest;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 40, 40, 40);

        // 提示
        ll.addView(tip("HT AI翻译 v5.0\n接收自动翻译 + 点[文A]按钮选版本"));

        // API Key
        ll.addView(lab("API Key:"));
        etKey = edit(readCfg("api_key", ""));
        ll.addView(etKey);

        // API URL
        ll.addView(lab("API URL:"));
        etUrl = edit(readCfg("api_url", "https://api.openai.com/v1/chat/completions"));
        ll.addView(etUrl);

        // 获取模型按钮
        btnFetch = btn("📡 获取模型列表");
        btnFetch.setOnClickListener(v -> fetchModels());
        ll.addView(btnFetch);

        // 模型
        ll.addView(lab("模型:"));
        etModel = edit(readCfg("model", ""));
        etModel.setHint("先获取后选择");
        ll.addView(etModel);

        ll.addView(div());

        // Prompts
        ll.addView(lab("🇨🇳 接收翻译 Prompt (外语→中文):"));
        etPromptZH = bigEdit(readPrompt("ZH"));
        ll.addView(etPromptZH);

        ll.addView(lab("🇬🇧 英语 Prompt (发送):"));
        etPromptEN = bigEdit(readPrompt("EN"));
        ll.addView(etPromptEN);

        ll.addView(lab("🇷🇺 俄语 Prompt (发送):"));
        etPromptRU = bigEdit(readPrompt("RU"));
        ll.addView(etPromptRU);

        ll.addView(lab("🇺🇦 乌克兰语 Prompt (发送):"));
        etPromptUK = bigEdit(readPrompt("UK"));
        ll.addView(etPromptUK);

        // 保存
        btnSave = btn("💾 保存全部配置");
        btnSave.setOnClickListener(v -> saveAll());
        ll.addView(btnSave);

        // 测试
        btnTest = btn("🧪 测试翻译");
        btnTest.setOnClickListener(v -> testTranslate());
        ll.addView(btnTest);

        sv.addView(ll);
        setContentView(sv);
    }

    // ────────────────────────────────
    // UI 工厂方法
    // ────────────────────────────────

    private TextView lab(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(0, 20, 0, 5);
        return tv;
    }

    private TextView tip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setPadding(0, 0, 0, 20);
        return tv;
    }

    private EditText edit(String text) {
        EditText et = new EditText(this);
        et.setText(text);
        return et;
    }

    private EditText bigEdit(String text) {
        EditText et = new EditText(this);
        et.setText(text);
        et.setMinLines(3);
        et.setMaxLines(8);
        et.setVerticalScrollBarEnabled(true);
        return et;
    }

    private Button btn(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private View div() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 2));
        v.setBackgroundColor(Color.parseColor("#33000000"));
        v.setPadding(0, 20, 0, 20);
        return v;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ────────────────────────────────
    // Root 命令执行
    // ────────────────────────────────

    private String runRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) {
                sb.append(l).append("\n");
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    // ────────────────────────────────
    // 配置读写
    // ────────────────────────────────

    private String readCfg(String key, String def) {
        String all = runRoot("cat /data/local/tmp/htai_config.txt 2>/dev/null");
        if (all == null || all.isEmpty()) return def;

        String[] lines = all.split("\n");
        for (String line : lines) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return def;
    }

    private String readPrompt(String section) {
        String all = runRoot("cat /data/local/tmp/htai_prompts.txt 2>/dev/null");
        if (all == null || all.isEmpty()) return "";

        String[] parts = all.split("###");
        StringBuilder sb = new StringBuilder();
        boolean inSection = false;

        for (String part : parts) {
            if (part.startsWith(section + "###")) {
                inSection = true;
                continue;
            }
            if (inSection) {
                if (part.startsWith("EN###") || part.startsWith("RU###")
                        || part.startsWith("UK###") || part.startsWith("ZH###")) {
                    break;
                }
                sb.append(part);
            }
        }
        return sb.toString().trim();
    }

    // ────────────────────────────────
    // 功能按钮
    // ────────────────────────────────

    private void fetchModels() {
        String key = etKey.getText().toString().trim();
        if (key.isEmpty()) {
            toast("先填 Key");
            return;
        }
        btnFetch.setEnabled(false);
        btnFetch.setText("获取中...");

        String url = etUrl.getText().toString().trim();

        new Thread(() -> {
            try {
                List<String> models = AITranslator.fetchModels(key, url);
                runOnUiThread(() -> {
                    btnFetch.setEnabled(true);
                    btnFetch.setText("📡 获取模型列表");
                    if (models.isEmpty()) {
                        toast("未获取到");
                    } else {
                        showPicker(models);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnFetch.setEnabled(true);
                    btnFetch.setText("📡 获取模型列表");
                    toast("失败: " + e.getMessage());
  

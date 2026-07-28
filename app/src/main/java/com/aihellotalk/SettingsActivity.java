package com.aihellotalk;

import android.app.Activity;
import android.app.AlertDialog;
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

        ll.addView(tip("HT AI翻译 v5.0\n接收自动翻译 + 点[文A]按钮选版本"));

        ll.addView(lab("API Key:"));
        etKey = edit(readCfg("api_key", ""));
        ll.addView(etKey);

        ll.addView(lab("API URL:"));
        etUrl = edit(readCfg("api_url", "https://api.openai.com/v1/chat/completions"));
        ll.addView(etUrl);

        btnFetch = btn("📡 获取模型列表");
        btnFetch.setOnClickListener(v -> fetchModels());
        ll.addView(btnFetch);

        ll.addView(lab("模型:"));
        etModel = edit(readCfg("model", ""));
        etModel.setHint("先获取后选择");
        ll.addView(etModel);

        ll.addView(div());

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

        btnSave = btn("💾 保存全部配置");
        btnSave.setOnClickListener(v -> saveAll());
        ll.addView(btnSave);

        btnTest = btn("🧪 测试翻译");
        btnTest.setOnClickListener(v -> testTranslate());
        ll.addView(btnTest);

        sv.addView(ll);
        setContentView(sv);
    }

    // ── UI 工厂 ──

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

    // ── Root ──

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

    // ── 配置读写 ──

    private String readCfg(String key, String def) {
        String all = runRoot("cat /data/local/tmp/htai_config.txt 2>/dev/null");
        if (all == null || all.isEmpty()) return def;
        for (String line : all.split("\n")) {
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

    // ── 获取模型列表 ──

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
                        showModelPicker(models);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnFetch.setEnabled(true);
                    btnFetch.setText("📡 获取模型列表");
                    toast("失败: " + e.getMessage());
                });
            }
        }).start();
    }

    private void showModelPicker(List<String> models) {
        String[] items = models.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("选模型(" + items.length + "个)")
                .setItems(items, (dialog, which) -> etModel.setText(items[which]))
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 保存配置 ──

    private void saveAll() {
        btnSave.setEnabled(false);
        btnSave.setText("保存中...");

        String key = etKey.getText().toString().trim();
        String url = etUrl.getText().toString().trim();
        String mdl = etModel.getText().toString().trim();
        String zh = etPromptZH.getText().toString().trim();
        String en = etPromptEN.getText().toString().trim();
        String ru = etPromptRU.getText().toString().trim();
        String uk = etPromptUK.getText().toString().trim();

        new Thread(() -> {
            try {
                String cfg = "cat > /data/local/tmp/htai_config.txt << 'EOF'\n"
                        + "api_key=" + key + "\n"
                        + "api_url=" + url + "\n"
                        + "model=" + mdl + "\n"
                        + "EOF\n";
                runRoot(cfg);

                String prompts = "cat > /data/local/tmp/htai_prompts.txt << 'EOF'\n"
                        + "###ZH###\n" + zh + "\n"
                        + "###EN###\n" + en + "\n"
                        + "###RU###\n" + ru + "\n"
                        + "###UK###\n" + uk + "\n"
                        + "EOF\n";
                runRoot(prompts);

                runRoot("chmod 644 /data/local/tmp/htai_config.txt /data/local/tmp/htai_prompts.txt");
                AITranslator.savePrompts(zh, en, ru, uk);

                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    btnSave.setText("💾 保存全部配置");
                    toast("✅ 已保存！强制停止 HelloTalk 后重开");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    btnSave.setText("💾 保存全部配置");
                    toast("❌ " + e.getMessage());
                });
            }
        }).start();
    }

    // ── 测试翻译 ──

    private void testTranslate() {
        String key = etKey.getText().toString().trim();
        if (key.isEmpty()) {
            toast("先填 Key");
            return;
        }
        String mdl = etModel.getText().toString().trim();
        if (mdl.isEmpty()) {
            toast("先选模型");
            return;
        }
        btnTest.setEnabled(false);
        btnTest.setText("翻译中...");
        String url = etUrl.getText().toString().trim();

        new Thread(() -> {
            try {
                AITranslator.init(key, url, mdl);
                String result = AITranslator.translateTest("你好世界", "English");
                runOnUiThread(() -> {
                    btnTest.setEnabled(true);
                    btnTest.setText("🧪 测试翻译");
                    if ("你好世界".equals(result)) {
                        toast("❌ 未翻译");
                    } else {
                        toast("✅ " + result);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnTest.setEnabled(true);
                    btnTest.setText("🧪 测试翻译");
                    toast("❌ " + e.getMessage());
                });
            }
        }).start();
    }
}

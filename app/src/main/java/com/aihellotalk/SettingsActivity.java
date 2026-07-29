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
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

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
        etKey = edit("");
        etKey.setHint("输入你的 API Key");
        ll.addView(etKey);

        ll.addView(lab("API URL:"));
        etUrl = edit("https://api.openai.com/v1/chat/completions");
        ll.addView(etUrl);

        btnFetch = btn("📡 获取模型列表");
        btnFetch.setOnClickListener(v -> fetchModels());
        ll.addView(btnFetch);

        ll.addView(lab("模型:"));
        etModel = edit("");
        etModel.setHint("先获取后选择，或手动输入");
        ll.addView(etModel);

        ll.addView(div());

        ll.addView(lab("🇨🇳 接收翻译 Prompt (外语→中文):"));
        etPromptZH = bigEdit("");
        ll.addView(etPromptZH);

        ll.addView(lab("🇬🇧 英语 Prompt (发送):"));
        etPromptEN = bigEdit("");
        ll.addView(etPromptEN);

        ll.addView(lab("🇷🇺 俄语 Prompt (发送):"));
        etPromptRU = bigEdit("");
        ll.addView(etPromptRU);

        ll.addView(lab("🇺🇦 乌克兰语 Prompt (发送):"));
        etPromptUK = bigEdit("");
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

    // ── 只在保存时读取配置 ──

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

    // ── 获取模型列表（自动尝试多种地址）──

    private void fetchModels() {
        String key = etKey.getText().toString().trim();
        if (key.isEmpty()) {
            toast("先填 Key");
            return;
        }
        btnFetch.setEnabled(false);
        btnFetch.setText("获取中...");
        String baseUrl = etUrl.getText().toString().trim();

        new Thread(() -> {
            try {
                List<String> models = autoFetchModels(key, baseUrl);
                runOnUiThread(() -> {
                    btnFetch.setEnabled(true);
                    btnFetch.setText("📡 获取模型列表");
                    if (models.isEmpty()) {
                        // 全部失败，提示手动输入
                        new AlertDialog.Builder(SettingsActivity.this)
                                .setTitle("获取失败")
                                .setMessage("自动尝试了多种地址均无法获取模型列表。\n请检查 API Key 和 URL 是否正确，或手动输入模型名。")
                                .setPositiveButton("知道了", null)
                                .show();
                    } else {
                        showModelPicker(models);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnFetch.setEnabled(true);
                    btnFetch.setText("📡 获取模型列表");
                    // 显示详细错误，并允许手动输入
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("获取失败")
                            .setMessage("错误: " + e.getMessage() + "\n\n你可以手动输入模型名。")
                            .setPositiveButton("手动输入", (dialog, which) -> {
                                // 让用户聚焦到模型输入框
                                etModel.requestFocus();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
            }
        }).start();
    }

    // 自动尝试多种地址获取模型列表
    private List<String> autoFetchModels(String key, String baseUrl) throws Exception {
        // 从 baseUrl 提取基础地址
        String base = baseUrl;
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        // 去掉末尾的 /v1 或 /v1/
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        } else if (base.endsWith("/v1/")) {
            base = base.substring(0, base.length() - 4);
        }
        // 确保末尾有 /
        if (!base.endsWith("/")) base += "/";

        // 要尝试的地址列表
        String[] urlsToTry = {
                base + "v1/models",
                base + "models",
                base + "api/models"
        };

        List<String> lastErrors = new ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        for (String url : urlsToTry) {
            try {
                Request req = new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + key)
                        .get()
                        .build();

                try (Response resp = client.newCall(req).execute()) {
                    if (resp.isSuccessful()) {
                        String s = resp.body().string();
                        JSONObject json = new JSONObject(s);
                        JSONArray data = json.getJSONArray("data");
                        List<String> models = new ArrayList<>();
                        for (int i = 0; i < data.length(); i++) {
                            models.add(data.getJSONObject(i).getString("id"));
                        }
                        return models;
                    } else {
                        lastErrors.add(url + " -> HTTP " + resp.code());
                    }
                }
            } catch (Exception e) {
                lastErrors.add(url + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // 全部失败，抛出异常附带详细信息
        throw new Exception("尝试了以下地址均失败:\n" + String.join("\n", lastErrors));
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

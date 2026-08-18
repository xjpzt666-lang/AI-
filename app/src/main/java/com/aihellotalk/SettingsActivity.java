package com.aihellotalk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
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

    private EditText etKey, etUrl, etModel, etTemperature, etMaxTokens;
    private android.widget.Spinner spinnerReasoning;
    private EditText etPromptZH, etPromptEN, etPromptRU, etPromptUK, etPromptKO, etPromptES;
    private Button btnFetch, btnSave, btnTest;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        prefs = getSharedPreferences("htai_settings", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 40, 40, 40);

        ll.addView(tip("HT AI翻译 v5.0\n接收自动翻译 + 点[文A]按钮选版本"));

        ll.addView(lab("API Key:"));
        etKey = edit(prefs.getString("api_key", ""));
        etKey.setHint("输入你的 API Key");
        ll.addView(etKey);

        ll.addView(lab("API URL:"));
        etUrl = edit(prefs.getString("api_url", "https://www.wintoken.dev"));
        ll.addView(etUrl);

        btnFetch = btn("获取模型列表");
        btnFetch.setOnClickListener(v -> fetchModels());
        ll.addView(btnFetch);

        ll.addView(lab("模型:"));
        etModel = edit(prefs.getString("model", ""));
        etModel.setHint("先获取后选择，或手动输入");
        ll.addView(etModel);

        ll.addView(lab("Temperature (模型发散温度):"));
        etTemperature = edit(prefs.getString("temperature", "0.7"));
        etTemperature.setHint("0.0 到 2.0 之间，推荐 0.7");
        ll.addView(etTemperature);

        ll.addView(lab("最大输出长度 (Max Tokens):"));
        etMaxTokens = edit(prefs.getString("max_tokens", "8000"));
        etMaxTokens.setHint("建议设置 2000 到 8000，防止回答被截断");
        ll.addView(etMaxTokens);

        ll.addView(div());

        ll.addView(lab("思考深度 (Reasoning Effort):"));
        spinnerReasoning = new android.widget.Spinner(this);
        String[] efforts = {"默认(不干预)", "轻度思考", "中度思考", "深度思考"};
        android.widget.ArrayAdapter<String> effortAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, efforts);
        effortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerReasoning.setAdapter(effortAdapter);
        String savedEffort = prefs.getString("reasoning_effort", "default");
        for (int i = 0; i < efforts.length; i++) {
            if (efforts[i].equals(savedEffort)) {
                spinnerReasoning.setSelection(i);
                break;
            }
        }
        ll.addView(spinnerReasoning);

        ll.addView(div());

        ll.addView(lab("接收翻译 Prompt (外语→中文):"));
        etPromptZH = bigEdit(prefs.getString("prompt_zh", ""));
        ll.addView(etPromptZH);

        ll.addView(lab("英语 Prompt (发送):"));
        etPromptEN = bigEdit(prefs.getString("prompt_en", ""));
        ll.addView(etPromptEN);

        ll.addView(lab("俄语 Prompt (发送):"));
        etPromptRU = bigEdit(prefs.getString("prompt_ru", ""));
        ll.addView(etPromptRU);

        ll.addView(lab("乌克兰语 Prompt (发送):"));
        etPromptUK = bigEdit(prefs.getString("prompt_uk", ""));
        ll.addView(etPromptUK);

        ll.addView(lab("韩语 Prompt (发送):"));
        etPromptKO = bigEdit(prefs.getString("prompt_ko", ""));
        ll.addView(etPromptKO);

        ll.addView(lab("西班牙语 Prompt (发送):"));
        etPromptES = bigEdit(prefs.getString("prompt_es", ""));
        ll.addView(etPromptES);

        btnSave = btn("保存全部配置");
        btnSave.setOnClickListener(v -> saveAll());
        ll.addView(btnSave);

        btnTest = btn("测试翻译");
        btnTest.setOnClickListener(v -> testTranslate());
        ll.addView(btnTest);

        sv.addView(ll);
        setContentView(sv);
    }

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
                        || part.startsWith("UK###") || part.startsWith("ZH###")
                        || part.startsWith("KO###") || part.startsWith("ES###")) {
                    break;
                }
                sb.append(part);
            }
        }
        return sb.toString().trim();
    }

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
                    btnFetch.setText("获取模型列表");
                    if (models.isEmpty()) {
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
                    btnFetch.setText("获取模型列表");
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("获取失败")
                            .setMessage("错误: " + e.getMessage() + "\n\n你可以手动输入模型名。")
                            .setPositiveButton("手动输入", (dialog, which) -> {
                                etModel.requestFocus();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
            }
        }).start();
    }

    private List<String> autoFetchModels(String key, String baseUrl) throws Exception {
        String base = baseUrl;
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        } else if (base.endsWith("/v1/")) {
            base = base.substring(0, base.length() - 4);
        }
        if (!base.endsWith("/")) base += "/";

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

        throw new Exception("尝试了以下地址均失败:\n" + String.join("\n", lastErrors));
    }

    private void showModelPicker(List<String> models) {
        String[] items = models.toArray(new String[0]);
        boolean[] checked = new boolean[items.length];

        String savedModels = prefs.getString("model_list", "");
        if (!savedModels.isEmpty()) {
            String[] savedArr = savedModels.split(",");
            for (int i = 0; i < items.length; i++) {
                for (String saved : savedArr) {
                    if (items[i].equals(saved.trim())) {
                        checked[i] = true;
                        break;
                    }
                }
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择模型（最多4个）");

        builder.setMultiChoiceItems(items, checked, (dialog, which, isChecked) -> {
            checked[which] = isChecked;
        });

        builder.setPositiveButton("确定", (dialog, which) -> {
            List<String> selected = new ArrayList<>();
            for (int i = 0; i < items.length; i++) {
                if (checked[i]) {
                    selected.add(items[i]);
                }
            }

            if (selected.isEmpty()) {
                toast("请至少选择一个模型");
                return;
            }

            if (selected.size() > 4) {
                toast("最多只能选择4个模型");
                return;
            }

            String selectedStr = String.join(",", selected);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("model_list", selectedStr);
            editor.putString("model", selected.get(0));
            editor.apply();

            etModel.setText(selected.get(0));

            StringBuilder sb = new StringBuilder("已选择：");
            for (String s : selected) {
                sb.append("\n• ").append(s);
            }
            toast(sb.toString());
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

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
        String ko = etPromptKO.getText().toString().trim();
        String es = etPromptES.getText().toString().trim();

        String tempStr = etTemperature.getText().toString().trim();
        if (tempStr.isEmpty()) {
            tempStr = "0.7";
        }
        try {
            Double.parseDouble(tempStr);
        } catch (NumberFormatException e) {
            tempStr = "0.7";
        }

        String maxTokensStr = etMaxTokens.getText().toString().trim();
        if (maxTokensStr.isEmpty()) {
            maxTokensStr = "8000";
        }
        try {
            Integer.parseInt(maxTokensStr);
        } catch (NumberFormatException e) {
            maxTokensStr = "8000";
        }

        String effortStr = spinnerReasoning.getSelectedItem().toString();
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("reasoning_effort", effortStr);
        editor.putString("api_key", key);
       editor.putString("api_url", url);
        editor.putString("model", mdl);
        editor.putString("temperature", tempStr);
        editor.putString("max_tokens", maxTokensStr);
        editor.putString("prompt_zh", zh);
        editor.putString("prompt_en", en);
        editor.putString("prompt_ru", ru);
        editor.putString("prompt_uk", uk);
        editor.putString("prompt_ko", ko);
        editor.putString("prompt_es", es);
        editor.apply();

        String finalTempStr = tempStr;
        String finalMaxTokensStr = maxTokensStr;
        String finalEffortStr = effortStr;
       new Thread(() -> {
            try {
                String modelList = prefs.getString("model_list", "");
                String cfg = "cat > /data/local/tmp/htai_config.txt << 'EOF'\n"
                        + "api_key=" + key + "\n"
                        + "api_url=" + url + "\n"
                        + "model=" + mdl + "\n"
                        + "model_list=" + modelList + "\n"
                        + "temperature=" + finalTempStr + "\n"
                        + "max_tokens=" + finalMaxTokensStr + "\n"
                        + "reasoning_effort=" + finalEffortStr + "\n"
                        + "EOF\n";
               runRoot(cfg);

                String prompts = "cat > /data/local/tmp/htai_prompts.txt << 'EOF'\n"
                        + "###ZH###\n" + zh + "\n"
                        + "###EN###\n" + en + "\n"
                        + "###RU###\n" + ru + "\n"
                        + "###UK###\n" + uk + "\n"
                        + "###KO###\n" + ko + "\n"
                        + "###ES###\n" + es + "\n"
                        + "EOF\n";
                runRoot(prompts);

                runRoot("chmod 644 /data/local/tmp/htai_config.txt /data/local/tmp/htai_prompts.txt");

                try {
                    java.lang.reflect.Method m = AITranslator.class.getMethod("savePrompts", String.class, String.class, String.class, String.class, String.class, String.class);
                    m.invoke(null, zh, en, ru, uk, ko, es);
                } catch (NoSuchMethodException e) {
                    AITranslator.savePrompts(zh, en, ru, uk);
                }

                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    btnSave.setText("保存全部配置");
                    toast("配置已保存，请强制停止 HelloTalk 后重开生效");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnSave.setEnabled(true);
                    btnSave.setText("保存全部配置");
                    toast("保存失败: " + e.getMessage());
                });
            }
        }).start();
    }

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
                    btnTest.setText("测试翻译");
                    if ("你好世界".equals(result)) {
                        toast("未翻译");
                    } else {
                        toast("翻译结果: " + result);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnTest.setEnabled(true);
                    btnTest.setText("测试翻译");
                    toast("翻译失败: " + e.getMessage());
                });
            }
        }).start();
    }
}

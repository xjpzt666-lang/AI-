package com.aihellotalk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.*;
import java.io.*;
import java.util.List;

public class SettingsActivity extends Activity {

    private EditText editApiKey, editApiUrl, editModel;
    private EditText editReceive, editEn, editRu, editUk, editKo, editEs;
    private Spinner modelSpinner;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("htai_settings", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 48, 24, 24);

        // 标题
        TextView title = new TextView(this);
        title.setText("⚙️ HT AI 设置");
        title.setTextSize(22f);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 24);
        root.addView(title);

        // API 配置
        root.addView(createSectionLabel("🔑 API 配置"));
        editApiKey = createEdit("API Key", prefs.getString("api_key", ""));
        root.addView(editApiKey);
        editApiUrl = createEdit("API URL", prefs.getString("api_url", "https://www.wintoken.dev"));
        root.addView(editApiUrl);
        editModel = createEdit("模型名称", prefs.getString("model", ""));
        root.addView(editModel);

        // 模型列表下拉
        modelSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"加载中..."});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(adapter);
        root.addView(modelSpinner);

        Button fetchBtn = new Button(this);
        fetchBtn.setText("📥 获取模型列表");
        fetchBtn.setOnClickListener(v -> fetchModels());
        root.addView(fetchBtn);

        // Prompt 配置
        root.addView(createSectionLabel("📝 翻译指令配置"));

        editReceive = createMultiEdit("🇨🇳 中文接收 Prompt（收到外语时使用）", prefs.getString("prompt_receive", ""));
        root.addView(editReceive);
        editEn = createMultiEdit("🇺🇸 英语 Prompt（发送）", prefs.getString("prompt_en", ""));
        root.addView(editEn);
        editRu = createMultiEdit("🇷🇺 俄语 Prompt（发送）", prefs.getString("prompt_ru", ""));
        root.addView(editRu);
        editUk = createMultiEdit("🇺🇦 乌克兰语 Prompt（发送）", prefs.getString("prompt_uk", ""));
        root.addView(editUk);
        // ★ 新增：韩语和西班牙语
        editKo = createMultiEdit("🇰🇷 韩语 Prompt（发送）", prefs.getString("prompt_ko", ""));
        root.addView(editKo);
        editEs = createMultiEdit("🇪🇸 西班牙语 Prompt（发送）", prefs.getString("prompt_es", ""));
        root.addView(editEs);

        // 保存按钮
        Button saveBtn = new Button(this);
        saveBtn.setText("💾 保存全部配置");
        saveBtn.setTextSize(16f);
        saveBtn.setPadding(0, 16, 0, 16);
        saveBtn.setOnClickListener(v -> saveAll());
        root.addView(saveBtn);

        // ★ 保留你原来的测试翻译按钮
        Button testBtn = new Button(this);
        testBtn.setText("🧪 测试翻译");
        testBtn.setTextSize(16f);
        testBtn.setPadding(0, 16, 0, 16);
        testBtn.setOnClickListener(v -> testTranslation());
        root.addView(testBtn);

        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView createSectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(16f);
        label.setTextColor(Color.parseColor("#333333"));
        label.setPadding(0, 20, 0, 8);
        return label;
    }

    private EditText createEdit(String hint, String defaultValue) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(defaultValue);
        et.setSingleLine(true);
        et.setPadding(12, 8, 12, 8);
        et.setBackgroundColor(Color.parseColor("#F5F5F5"));
        et.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private EditText createMultiEdit(String hint, String defaultValue) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(defaultValue);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(3);
        et.setGravity(Gravity.TOP);
        et.setPadding(12, 8, 12, 8);
        et.setBackgroundColor(Color.parseColor("#F5F5F5"));
        et.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private void fetchModels() {
        String key = editApiKey.getText().toString().trim();
        String url = editApiUrl.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "请先填写 API Key", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                List<String> models = AITranslator.fetchModels(key, url);
                runOnUiThread(() -> {
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_item, models);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    modelSpinner.setAdapter(adapter);
                    String savedModel = prefs.getString("model", "");
                    if (!savedModel.isEmpty() && models.contains(savedModel)) {
                        modelSpinner.setSelection(models.indexOf(savedModel));
                    }
                    Toast.makeText(this, "获取到 " + models.size() + " 个模型", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "获取失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void saveAll() {
        String apiKey = editApiKey.getText().toString().trim();
        String apiUrl = editApiUrl.getText().toString().trim();
        String model = editModel.getText().toString().trim();

        if (model.isEmpty() && modelSpinner.getSelectedItem() != null) {
            model = modelSpinner.getSelectedItem().toString();
        }

        String zh = editReceive.getText().toString().trim();
        String en = editEn.getText().toString().trim();
        String ru = editRu.getText().toString().trim();
        String uk = editUk.getText().toString().trim();
        String ko = editKo.getText().toString().trim();
        String es = editEs.getText().toString().trim();

        prefs.edit()
                .putString("api_key", apiKey)
                .putString("api_url", apiUrl)
                .putString("model", model)
                .putString("prompt_receive", zh)
                .putString("prompt_en", en)
                .putString("prompt_ru", ru)
                .putString("prompt_uk", uk)
                .putString("prompt_ko", ko)
                .putString("prompt_es", es)
                .apply();

        // ★ 改为6个参数
        AITranslator.savePrompts(zh, en, ru, uk, ko, es);

        writePromptsToFile(zh, en, ru, uk, ko, es);

        Toast.makeText(this, "✅ 配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void writePromptsToFile(String zh, String en, String ru, String uk, String ko, String es) {
        try {
            File file = new File("/data/local/tmp/htai_prompts.txt");
            file.getParentFile().mkdirs();
            BufferedWriter w = new BufferedWriter(new FileWriter(file));
            w.write("###ZH###\n" + zh + "\n");
            w.write("###EN###\n" + en + "\n");
            w.write("###RU###\n" + ru + "\n");
            w.write("###UK###\n" + uk + "\n");
            w.write("###KO###\n" + ko + "\n");
            w.write("###ES###\n" + es + "\n");
            w.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ★ 保留你原来的测试翻译功能
    private void testTranslation() {
        String apiKey = editApiKey.getText().toString().trim();
        String apiUrl = editApiUrl.getText().toString().trim();
        String model = editModel.getText().toString().trim();

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "请先填写 API Key", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("测试翻译")
                .setMessage("请输入要翻译的中文")
                .setView(new EditText(this))
                .setPositiveButton("翻译成英文", (d, w) -> {
                    EditText input = (EditText) ((AlertDialog) d).findViewById(android.R.id.custom);
                    if (input != null) {
                        String text = input.getText().toString().trim();
                        if (!text.isEmpty()) {
                            doTranslation(text, "en", apiKey, apiUrl, model);
                        }
                    }
                })
                .setNegativeButton("翻译成俄文", (d, w) -> {
                    EditText input = (EditText) ((AlertDialog) d).findViewById(android.R.id.custom);
                    if (input != null) {
                        String text = input.getText().toString().trim();
                        if (!text.isEmpty()) {
                            doTranslation(text, "ru", apiKey, apiUrl, model);
                        }
                    }
                })
                .setNeutralButton("取消", null)
                .show();
    }

    private void doTranslation(String text, String lang, String key, String url, String model) {
        new Thread(() -> {
            try {
                AITranslator.init(key, url, model);
                String result = AITranslator.fromChinese(text, lang);
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle("翻译结果 (" + lang + ")")
                            .setMessage(result)
                            .setPositiveButton("确定", null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "翻译失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}

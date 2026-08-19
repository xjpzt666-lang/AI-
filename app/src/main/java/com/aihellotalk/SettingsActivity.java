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

    private EditText etKey, etUrl, etModel, etTemperature, etMaxTokens, etMaxChat, etBannedWords;
    private android.widget.Spinner spinnerReasoning;
    private EditText etPromptZH, etPromptEN, etPromptRU, etPromptUK, etPromptKO, etPromptES;
    private EditText etPromptAR, etPromptPT, etPromptFR, etPromptDE, etPromptIT;
    private EditText etPromptTR, etPromptNL, etPromptPL, etPromptKK, etPromptCS;
    private EditText etQuick1, etQuick2, etQuick3, etQuick4, etQuick5;

    private EditText etSearchPrompt;
    private Button btnFetch, btnSave, btnTest;
    private Button btnSearchPrompt;

    private ScrollView settingsScroll;
    private LinearLayout settingsContent;
    
    // 新增：用于控制折叠面板的容器和标题
    private LinearLayout advContentLayout;
    private TextView advHeaderTitle;
    private LinearLayout promptContentLayout;
    private TextView promptHeaderTitle;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        prefs = getSharedPreferences("htai_settings", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 40, 40, 40);

        settingsScroll = sv;
        settingsContent = ll;

        ll.addView(tip("HT AI翻译 v5.14 (带智能折叠)\n接收自动翻译 + 点[文A]按钮选版本"));

        etSearchPrompt = edit("");
        etSearchPrompt.setHint("🔍 搜索语言跳转，例如：阿拉伯、葡萄牙、法语");
        etSearchPrompt.setSingleLine(true);
        ll.addView(etSearchPrompt);

        btnSearchPrompt = btn("跳转到语言");
        btnSearchPrompt.setOnClickListener(v -> {
            String q = etSearchPrompt.getText().toString().trim();
            jumpToLanguage(q);
        });
        ll.addView(btnSearchPrompt);

        ll.addView(div());

        // ================= 常驻显示区：API 基础配置 =================
        ll.addView(lab("API Key:"));
        etKey = edit(prefs.getString("api_key", ""));
        etKey.setHint("输入你的 API Key");
        ll.addView(etKey);

        ll.addView(lab("API URL:"));
        etUrl = edit(prefs.getString("api_url", "https://api.openai.com/v1/chat/completions"));
        ll.addView(etUrl);

        btnFetch = btn("获取模型列表");
        btnFetch.setOnClickListener(v -> fetchModels());
        ll.addView(btnFetch);

        ll.addView(lab("模型:"));
        etModel = edit(prefs.getString("model", ""));
        etModel.setHint("先获取后选择，或手动输入");
        ll.addView(etModel);

        // ================= 折叠区 1：高级与安全设置 =================
        LinearLayout advHeaderLayout = createHeaderLayout();
        advHeaderTitle = new TextView(this);
        boolean isAdvExpanded = prefs.getBoolean("adv_expanded", false); // 读取记忆
        styleHeaderTitle(advHeaderTitle, isAdvExpanded ? "▼ ⚙️ 高级与安全设置 (点击折叠)" : "▶ ⚙️ 高级与安全设置 (点击展开)");
        advHeaderLayout.addView(advHeaderTitle);
        ll.addView(advHeaderLayout);

        advContentLayout = new LinearLayout(this);
        advContentLayout.setOrientation(LinearLayout.VERTICAL);
        advContentLayout.setVisibility(isAdvExpanded ? View.VISIBLE : View.GONE); // 恢复记忆状态
        advContentLayout.setPadding(20, 10, 0, 10);

        advContentLayout.addView(lab("Temperature (模型发散温度):"));
        etTemperature = edit(prefs.getString("temperature", "0.7"));
        etTemperature.setHint("0.0 到 2.0 之间，推荐 0.7");
        advContentLayout.addView(etTemperature);

        advContentLayout.addView(lab("上下文消息数 (Max Chat Messages):"));
        etMaxChat = edit(prefs.getString("max_chat_messages", "30"));
        etMaxChat.setHint("建议 20~60，越大越慢但记忆越久");
        advContentLayout.addView(etMaxChat);

        advContentLayout.addView(lab("最大输出长度 (Max Tokens):"));
        etMaxTokens = edit(prefs.getString("max_tokens", "8000"));
        etMaxTokens.setHint("建议设置 2000 到 8000，防止回答被截断");
        advContentLayout.addView(etMaxTokens);

        advContentLayout.addView(lab("全局违禁词库 (Banned Words & Symbols):"));
        etBannedWords = bigEdit(prefs.getString("banned_words", ""));
        etBannedWords.setHint("输入千万不能出现的词或标点，如：lol,破折号,;");
        advContentLayout.addView(etBannedWords);

        advContentLayout.addView(lab("思考深度 (Reasoning Effort):"));
        spinnerReasoning = new android.widget.Spinner(this);
        String[] efforts = {"默认(不干预)", "轻度思考", "中度思考", "深度思考"};
        android.widget.ArrayAdapter<String> effortAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, efforts);
        effortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerReasoning.setAdapter(effortAdapter);
        String savedEffort = prefs.getString("reasoning_effort", "default");
        int selectedIndex = 0;
        if ("low".equals(savedEffort)) selectedIndex = 1;
        else if ("medium".equals(savedEffort)) selectedIndex = 2;
        else if ("high".equals(savedEffort)) selectedIndex = 3;
        spinnerReasoning.setSelection(selectedIndex);
        advContentLayout.addView(spinnerReasoning);

        ll.addView(advContentLayout);
        setupToggle(advHeaderLayout, advHeaderTitle, advContentLayout, "⚙️ 高级与安全设置", "adv_expanded");

        // ================= 折叠区 2：语言专属指令 =================
        LinearLayout promptHeaderLayout = createHeaderLayout();
        promptHeaderTitle = new TextView(this);
        boolean isPromptExpanded = prefs.getBoolean("prompt_expanded", false); // 读取记忆
        styleHeaderTitle(promptHeaderTitle, isPromptExpanded ? "▼ 🌐 语言专属指令设置 (点击折叠)" : "▶ 🌐 语言专属指令设置 (点击展开)");
        promptHeaderLayout.addView(promptHeaderTitle);
        ll.addView(promptHeaderLayout);

        promptContentLayout = new LinearLayout(this);
        promptContentLayout.setOrientation(LinearLayout.VERTICAL);
        promptContentLayout.setVisibility(isPromptExpanded ? View.VISIBLE : View.GONE); // 恢复记忆状态
        promptContentLayout.setPadding(20, 10, 0, 10);

        promptContentLayout.addView(lab("接收翻译 Prompt (外语→中文):"));
        etPromptZH = bigEdit(prefs.getString("prompt_zh", ""));
        promptContentLayout.addView(etPromptZH);

        promptContentLayout.addView(lab("英语 Prompt (发送):"));
        etPromptEN = bigEdit(prefs.getString("prompt_en", ""));
        promptContentLayout.addView(etPromptEN);

        promptContentLayout.addView(lab("俄语 Prompt (发送):"));
        etPromptRU = bigEdit(prefs.getString("prompt_ru", ""));
        promptContentLayout.addView(etPromptRU);

        promptContentLayout.addView(lab("乌克兰语 Prompt (发送):"));
        etPromptUK = bigEdit(prefs.getString("prompt_uk", ""));
        promptContentLayout.addView(etPromptUK);

        promptContentLayout.addView(lab("韩语 Prompt (发送):"));
        etPromptKO = bigEdit(prefs.getString("prompt_ko", ""));
        promptContentLayout.addView(etPromptKO);

        promptContentLayout.addView(lab("西班牙语 Prompt (发送):"));
        etPromptES = bigEdit(prefs.getString("prompt_es", ""));
        promptContentLayout.addView(etPromptES);

        promptContentLayout.addView(div());

        promptContentLayout.addView(lab("阿拉伯语 Prompt (发送):"));
        etPromptAR = bigEdit(prefs.getString("prompt_ar", ""));
        promptContentLayout.addView(etPromptAR);

        promptContentLayout.addView(lab("葡萄牙语 Prompt (发送):"));
        etPromptPT = bigEdit(prefs.getString("prompt_pt", ""));
        promptContentLayout.addView(etPromptPT);

        promptContentLayout.addView(lab("法语 Prompt (发送):"));
        etPromptFR = bigEdit(prefs.getString("prompt_fr", ""));
        promptContentLayout.addView(etPromptFR);

        promptContentLayout.addView(lab("德语 Prompt (发送):"));
        etPromptDE = bigEdit(prefs.getString("prompt_de", ""));
        promptContentLayout.addView(etPromptDE);

        promptContentLayout.addView(lab("意大利语 Prompt (发送):"));
        etPromptIT = bigEdit(prefs.getString("prompt_it", ""));
        promptContentLayout.addView(etPromptIT);

        promptContentLayout.addView(lab("土耳其语 Prompt (发送):"));
        etPromptTR = bigEdit(prefs.getString("prompt_tr", ""));
        promptContentLayout.addView(etPromptTR);

        promptContentLayout.addView(lab("荷兰语 Prompt (发送):"));
        etPromptNL = bigEdit(prefs.getString("prompt_nl", ""));
        promptContentLayout.addView(etPromptNL);

        promptContentLayout.addView(lab("波兰语 Prompt (发送):"));
        etPromptPL = bigEdit(prefs.getString("prompt_pl", ""));
        promptContentLayout.addView(etPromptPL);

        promptContentLayout.addView(lab("哈萨克语 Prompt (发送):"));
        etPromptKK = bigEdit(prefs.getString("prompt_kk", ""));
        promptContentLayout.addView(etPromptKK);

        promptContentLayout.addView(lab("捷克语 Prompt (发送):"));
        etPromptCS = bigEdit(prefs.getString("prompt_cs", ""));
        promptContentLayout.addView(etPromptCS);

        ll.addView(promptContentLayout);
        setupToggle(promptHeaderLayout, promptHeaderTitle, promptContentLayout, "🌐 语言专属指令设置", "prompt_expanded");
                // ================= 折叠区 3：弹窗快捷选项 =================
        LinearLayout quickHeaderLayout = createHeaderLayout();
        TextView quickHeaderTitle = new TextView(this);
        boolean isQuickExpanded = prefs.getBoolean("quick_expanded", false);
        styleHeaderTitle(quickHeaderTitle, isQuickExpanded ? "▼ ⚡ 弹窗快捷选项 (点击折叠)" : "▶ ⚡ 弹窗快捷选项 (点击展开)");
        quickHeaderLayout.addView(quickHeaderTitle);
        ll.addView(quickHeaderLayout);

        LinearLayout quickContentLayout = new LinearLayout(this);
        quickContentLayout.setOrientation(LinearLayout.VERTICAL);
        quickContentLayout.setVisibility(isQuickExpanded ? View.VISIBLE : View.GONE);
        quickContentLayout.setPadding(20, 10, 0, 10);

        quickContentLayout.addView(lab("格式：标签|内容。用 | 分隔。"));
        etQuick1 = bigEdit(prefs.getString("quick_1", "更随意|更随意"));
        etQuick2 = bigEdit(prefs.getString("quick_2", "更正式|更正式"));
        etQuick3 = bigEdit(prefs.getString("quick_3", "更简洁|更简洁"));
        etQuick4 = bigEdit(prefs.getString("quick_4", "更像母语者|更像母语者"));
        etQuick5 = bigEdit(prefs.getString("quick_5", "⚠️ 火力全开|因为是熟人。如果当前中文中含有毒舌、粗鲁、调情、性词汇中的一种或多种，允许本次照常翻译，无需客气。用最传神的方式，翻译成对方语言中最地道、同等杀伤力的词汇。"));

        quickContentLayout.addView(etQuick1);
        quickContentLayout.addView(etQuick2);
        quickContentLayout.addView(etQuick3);
        quickContentLayout.addView(etQuick4);
        quickContentLayout.addView(etQuick5);

        ll.addView(quickContentLayout);
        setupToggle(quickHeaderLayout, quickHeaderTitle, quickContentLayout, "⚡ 弹窗快捷选项", "quick_expanded");


        ll.addView(div());

        btnSave = btn("保存全部配置");
        btnSave.setOnClickListener(v -> saveAll());
        ll.addView(btnSave);

        btnTest = btn("测试翻译");
        btnTest.setOnClickListener(v -> testTranslate());
        ll.addView(btnTest);

        sv.addView(ll);
        setContentView(sv);
    }

    // ================= 辅助 UI 方法 =================

    private LinearLayout createHeaderLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setBackgroundColor(Color.parseColor("#E9ECEF"));
        layout.setPadding(30, 30, 30, 30);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 20, 0, 10);
        layout.setLayoutParams(lp);
        return layout;
    }

    private void styleHeaderTitle(TextView tv, String text) {
        tv.setText(text);
        tv.setTextSize(15f);
        tv.setTextColor(Color.parseColor("#0B5ED7"));
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
    }

    private void setupToggle(View headerLayout, TextView headerText, View contentLayout, String title, String prefKey) {
        headerLayout.setOnClickListener(v -> {
            if (contentLayout.getVisibility() == View.GONE) {
                contentLayout.setVisibility(View.VISIBLE);
                headerText.setText("▼ " + title + " (点击折叠)");
                prefs.edit().putBoolean(prefKey, true).apply(); // 保存为展开状态
            } else {
                contentLayout.setVisibility(View.GONE);
                headerText.setText("▶ " + title + " (点击展开)");
                prefs.edit().putBoolean(prefKey, false).apply(); // 保存为折叠状态
            }
        });
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

    private void jumpToLanguage(String q) {
        if (q == null || q.trim().isEmpty()) {
            toast("请先输入语言名");
            return;
        }

        String s = q.trim().toLowerCase();

        // 智能联动：搜索时自动展开折叠面板
        if (promptContentLayout != null && promptContentLayout.getVisibility() == View.GONE) {
            promptContentLayout.setVisibility(View.VISIBLE);
            if (promptHeaderTitle != null) promptHeaderTitle.setText("▼ 🌐 语言专属指令设置 (点击折叠)");
            prefs.edit().putBoolean("prompt_expanded", true).apply(); // 同步记录展开状态
        }

        if (s.contains("接收") || s.contains("中文")) {
            scrollToView(etPromptZH);
            return;
        }
        if (s.contains("英语") || s.contains("英文") || s.contains("en")) {
            scrollToView(etPromptEN);
            return;
        }
        if (s.contains("俄语") || s.contains("俄罗斯") || s.contains("ru")) {
            scrollToView(etPromptRU);
            return;
        }
        if (s.contains("乌克兰") || s.contains("uk")) {
            scrollToView(etPromptUK);
            return;
        }
        if (s.contains("韩语") || s.contains("韩国") || s.contains("ko")) {
            scrollToView(etPromptKO);
            return;
        }
        if (s.contains("西班牙") || s.contains("es")) {
            scrollToView(etPromptES);
            return;
        }
        if (s.contains("阿拉伯") || s.contains("ar")) {
            scrollToView(etPromptAR);
            return;
        }
        if (s.contains("葡萄牙") || s.contains("pt")) {
            scrollToView(etPromptPT);
            return;
        }
        if (s.contains("法语") || s.contains("法国") || s.contains("fr")) {
            scrollToView(etPromptFR);
            return;
        }
        if (s.contains("德语") || s.contains("德国") || s.contains("de")) {
            scrollToView(etPromptDE);
            return;
        }
        if (s.contains("意大利") || s.contains("it")) {
            scrollToView(etPromptIT);
            return;
        }
        if (s.contains("土耳其") || s.contains("tr")) {
            scrollToView(etPromptTR);
            return;
        }
        if (s.contains("荷兰") || s.contains("nl")) {
            scrollToView(etPromptNL);
            return;
        }
        if (s.contains("波兰") || s.contains("pl")) {
            scrollToView(etPromptPL);
            return;
        }
        if (s.contains("哈萨克") || s.contains("kk")) {
            scrollToView(etPromptKK);
            return;
        }
        if (s.contains("捷克") || s.contains("cs")) {
            scrollToView(etPromptCS);
            return;
        }

        toast("没找到匹配语言");
    }

    private void scrollToView(View target) {
        if (target == null || settingsScroll == null) return;
        target.post(() -> {
            int y = target.getTop();
            settingsScroll.smoothScrollTo(0, Math.max(0, y - 40));
        });
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

        String ar = etPromptAR.getText().toString().trim();
        String pt = etPromptPT.getText().toString().trim();
        String fr = etPromptFR.getText().toString().trim();
        String de = etPromptDE.getText().toString().trim();
        String it = etPromptIT.getText().toString().trim();
        String tr = etPromptTR.getText().toString().trim();
        String nl = etPromptNL.getText().toString().trim();
        String pl = etPromptPL.getText().toString().trim();
        String kk = etPromptKK.getText().toString().trim();
        String cs = etPromptCS.getText().toString().trim();

        String tempStr = etTemperature.getText().toString().trim();
        String maxChatStr = etMaxChat.getText().toString().trim();
        if (maxChatStr.isEmpty()) maxChatStr = "30";
        if (tempStr.isEmpty()) tempStr = "0.7";
        try { Double.parseDouble(tempStr); } catch (NumberFormatException e) { tempStr = "0.7"; }

        String maxTokensStr = etMaxTokens.getText().toString().trim();
        String bannedStr = etBannedWords.getText().toString().trim();
        if (maxTokensStr.isEmpty()) maxTokensStr = "8000";
        try { Integer.parseInt(maxTokensStr); } catch (NumberFormatException e) { maxTokensStr = "8000"; }

        int selectedPos = spinnerReasoning.getSelectedItemPosition();
        String effortStr = "default";
        if (selectedPos == 1) effortStr = "low";
        else if (selectedPos == 2) effortStr = "medium";
        else if (selectedPos == 3) effortStr = "high";
        
        String q1 = etQuick1.getText().toString().trim();
        String q2 = etQuick2.getText().toString().trim();
        String q3 = etQuick3.getText().toString().trim();
        String q4 = etQuick4.getText().toString().trim();
        String q5 = etQuick5.getText().toString().trim();

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("reasoning_effort", effortStr);
        editor.putString("api_key", key);
        editor.putString("api_url", url);
        editor.putString("model", mdl);
        editor.putString("temperature", tempStr);
        editor.putString("max_chat_messages", maxChatStr);
        editor.putString("max_tokens", maxTokensStr);
        editor.putString("banned_words", bannedStr);
        editor.putString("prompt_zh", zh);
        editor.putString("prompt_en", en);
        editor.putString("prompt_ru", ru);
        editor.putString("prompt_uk", uk);
        editor.putString("prompt_ko", ko);
        editor.putString("prompt_es", es);
        editor.putString("prompt_ar", ar);
        editor.putString("prompt_pt", pt);
        editor.putString("prompt_fr", fr);
        editor.putString("prompt_de", de);
        editor.putString("prompt_it", it);
        editor.putString("prompt_tr", tr);
        editor.putString("prompt_nl", nl);
        editor.putString("prompt_pl", pl);
        editor.putString("prompt_kk", kk);
        editor.putString("prompt_cs", cs);
        editor.putString("quick_1", q1);
        editor.putString("quick_2", q2);
        editor.putString("quick_3", q3);
        editor.putString("quick_4", q4);
        editor.putString("quick_5", q5);
        editor.apply();

        final String finalTempStr = tempStr;
        final String finalMaxTokensStr = maxTokensStr;
        final String finalEffortStr = effortStr;
        final String finalMaxChatStr = maxChatStr;
        final String finalBannedStr = bannedStr;
        final String fq1 = q1, fq2 = q2, fq3 = q3, fq4 = q4, fq5 = q5;
        
        new Thread(() -> {
            try {
                String modelList = prefs.getString("model_list", "");
                String cfg = "cat > /data/local/tmp/htai_config.txt << 'EOF'\n"
                        + "api_key=" + key + "\n"
                        + "api_url=" + url + "\n"
                        + "model=" + mdl + "\n"
                        + "model_list=" + modelList + "\n"
                        + "temperature=" + finalTempStr + "\n"
                        + "max_chat_messages=" + finalMaxChatStr + "\n"
                        + "max_tokens=" + finalMaxTokensStr + "\n"
                        + "banned_words=" + finalBannedStr + "\n"
                        + "reasoning_effort=" + finalEffortStr + "\n"
                        + "quick_1=" + fq1 + "\n"
                        + "quick_2=" + fq2 + "\n"
                        + "quick_3=" + fq3 + "\n"
                        + "quick_4=" + fq4 + "\n"
                        + "quick_5=" + fq5 + "\n"
                        + "EOF\n";
                runRoot(cfg);

                String prompts = "cat > /data/local/tmp/htai_prompts.txt << 'EOF'\n"
                        + "###ZH###\n" + zh + "\n"
                        + "###EN###\n" + en + "\n"
                        + "###RU###\n" + ru + "\n"
                        + "###UK###\n" + uk + "\n"
                        + "###KO###\n" + ko + "\n"
                        + "###ES###\n" + es + "\n"
                        + "###AR###\n" + ar + "\n"
                        + "###PT###\n" + pt + "\n"
                        + "###FR###\n" + fr + "\n"
                        + "###DE###\n" + de + "\n"
                        + "###IT###\n" + it + "\n"
                        + "###TR###\n" + tr + "\n"
                        + "###NL###\n" + nl + "\n"
                        + "###PL###\n" + pl + "\n"
                        + "###KK###\n" + kk + "\n"
                        + "###CS###\n" + cs + "\n"
                        + "EOF\n";
                runRoot(prompts);

                runRoot("chmod 644 /data/local/tmp/htai_config.txt /data/local/tmp/htai_prompts.txt");

                try {
                    java.lang.reflect.Method m = AITranslator.class.getMethod(
                            "savePrompts",
                            String.class, String.class, String.class, String.class,
                            String.class, String.class, String.class, String.class,
                            String.class, String.class, String.class, String.class,
                            String.class, String.class, String.class, String.class);
                    m.invoke(null, zh, en, ru, uk, ko, es, ar, pt, fr, de, it, tr, nl, pl, kk, cs);
                } catch (NoSuchMethodException ignored) {
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

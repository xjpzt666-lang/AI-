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
    private EditText etQuick1Tag, etQuick1Content, etQuick2Tag, etQuick2Content;
    private EditText etQuick3Tag, etQuick3Content, etQuick4Tag, etQuick4Content;
    private EditText etQuick5;
// API 1 新增字段
private EditText etWeight1;
private android.widget.Spinner spinnerDir1;
// API 2-5 思考模式
private android.widget.Spinner spinnerReasoning2, spinnerReasoning3, spinnerReasoning4, spinnerReasoning5;
// ★ 多API备用配置
private EditText etKey2, etUrl2, etModel2, etWeight2;
private android.widget.Spinner spinnerDir2;
private EditText etKey3, etUrl3, etModel3, etWeight3;
private android.widget.Spinner spinnerDir3;
private EditText etKey4, etUrl4, etModel4, etWeight4;
private android.widget.Spinner spinnerDir4;
private EditText etKey5, etUrl5, etModel5, etWeight5;
private android.widget.Spinner spinnerDir5;
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

ll.addView(lab("调用权重 (数字越大用得越多，建议1~10):"));
etWeight1 = edit(prefs.getString("api_weight", "3"));
etWeight1.setHint("3");
ll.addView(etWeight1);

ll.addView(lab("翻译方向:"));
spinnerDir1 = new android.widget.Spinner(this);
String[] dirs = {"发送+接收", "仅接收", "仅发送"};
android.widget.ArrayAdapter<String> dirAdapter1 = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, dirs);
dirAdapter1.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
spinnerDir1.setAdapter(dirAdapter1);
spinnerDir1.setSelection(prefs.getInt("api_direction", 0));
ll.addView(spinnerDir1);

ll.addView(lab("思考模式:"));
spinnerReasoning = new android.widget.Spinner(this);
String[] efforts = {"默认(不干预)", "轻度思考", "中度思考", "深度思考"};
android.widget.ArrayAdapter<String> effAdapter1 = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, efforts);
effAdapter1.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
spinnerReasoning.setAdapter(effAdapter1);                      // ← 改
String savedEff1 = prefs.getString("reasoning_effort", "default");
int selEff1 = 0;
if ("low".equals(savedEff1)) selEff1 = 1;
else if ("medium".equals(savedEff1)) selEff1 = 2;
else if ("high".equals(savedEff1)) selEff1 = 3;
spinnerReasoning.setSelection(selEff1);                        // ← 改
spinnerReasoning.setTag("reasoning1");                         // ← 改
ll.addView(spinnerReasoning);                                  // ← 改

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


        ll.addView(advContentLayout);
        setupToggle(advHeaderLayout, advHeaderTitle, advContentLayout, "⚙️ 高级与安全设置", "adv_expanded");
// ================= 折叠区 1.5：多API智能密钥配置 =================
LinearLayout apiHeaderLayout = createHeaderLayout();
TextView apiHeaderTitle = new TextView(this);
boolean isApiExpanded = prefs.getBoolean("api_expanded", true);
styleHeaderTitle(apiHeaderTitle, isApiExpanded ? "▼ 🔄 多API智能密钥配置（最多5個） (点击折叠)" : "▶ 🔄 多API智能密钥配置（最多5個） (点击展开)");
apiHeaderLayout.addView(apiHeaderTitle);
ll.addView(apiHeaderLayout);

LinearLayout apiContentLayout = new LinearLayout(this);
apiContentLayout.setOrientation(LinearLayout.VERTICAL);
apiContentLayout.setVisibility(isApiExpanded ? View.VISIBLE : View.GONE);
apiContentLayout.setPadding(20, 10, 0, 10);

// 辅助背景
android.graphics.drawable.GradientDrawable titleBg = new android.graphics.drawable.GradientDrawable();
titleBg.setColor(Color.parseColor("#E8EAF6"));
titleBg.setCornerRadius(8f);

// --- API 2 ---
TextView t2 = new TextView(this); t2.setText("🟢 备用 API 2"); t2.setTextColor(Color.parseColor("#198754")); t2.setTextSize(15f); t2.setTypeface(null, android.graphics.Typeface.BOLD); t2.setPadding(20, 20, 20, 20); t2.setBackground(titleBg);
LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp2.setMargins(0, 30, 0, 10); t2.setLayoutParams(lp2);
apiContentLayout.addView(t2);
apiContentLayout.addView(lab("API Key 2:")); etKey2 = edit(prefs.getString("api_key_2", "")); apiContentLayout.addView(etKey2);
apiContentLayout.addView(lab("API URL 2:")); etUrl2 = edit(prefs.getString("api_url_2", "")); apiContentLayout.addView(etUrl2);
apiContentLayout.addView(lab("模型 2:")); etModel2 = edit(prefs.getString("model_2", "")); apiContentLayout.addView(etModel2);
Button btnFetch2 = btn("获取模型"); btnFetch2.setOnClickListener(v -> fetchModelsForApi(etKey2.getText().toString().trim(), etUrl2.getText().toString().trim(), etModel2)); apiContentLayout.addView(btnFetch2);
apiContentLayout.addView(lab("调用权重 2:")); etWeight2 = edit(prefs.getString("api_weight_2", "3")); apiContentLayout.addView(etWeight2);
apiContentLayout.addView(lab("翻译方向 2:")); spinnerDir2 = new android.widget.Spinner(this); android.widget.ArrayAdapter<String> dirAdapter2 = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, dirs); dirAdapter2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinnerDir2.setAdapter(dirAdapter2); spinnerDir2.setSelection(prefs.getInt("api_direction_2", 0)); apiContentLayout.addView(spinnerDir2);
apiContentLayout.addView(lab("思考模式 2:")); spinnerReasoning2 = new android.widget.Spinner(this); android.widget.ArrayAdapter<String> effAdapter2 = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, efforts2); effAdapter2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinnerReasoning2.setAdapter(effAdapter2); String savedEff2 = prefs.getString("reasoning_effort_2", "default"); int selEff2 = 0; if ("low".equals(savedEff2)) selEff2 = 1; else if ("medium".equals(savedEff2)) selEff2 = 2; else if ("high".equals(savedEff2)) selEff2 = 3; spinnerReasoning2.setSelection(selEff2); apiContentLayout.addView(spinnerReasoning2);

// --- API 3 ---
TextView t3 = new TextView(this); t3.setText("🟠 备用 API 3"); t3.setTextColor(Color.parseColor("#D97706")); t3.setTextSize(15f); t3.setTypeface(null, android.graphics.Typeface.BOLD); t3.setPadding(20, 20, 20, 20); t3.setBackground(titleBg);
LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp3.setMargins(0, 40, 0, 10); t3.setLayoutParams(lp3);
apiContentLayout.addView(t3);
apiContentLayout.addView(lab("API Key 3:")); etKey3 = edit(prefs.getString("api_key_3", "")); apiContentLayout.addView(etKey3);
apiContentLayout.addView(lab("API URL 3:")); etUrl3 = edit(prefs.getString("api_url_3", "")); apiContentLayout.addView(etUrl3);
apiContentLayout.addView(lab("模型 3:")); etModel3 = edit(prefs.getString("model_3", "")); apiContentLayout.addView(etModel3);
Button btnFetch3 = btn("获取模型"); btnFetch3.setOnClickListener(v -> fetchModelsForApi(etKey3.getText().toString().trim(), etUrl3.getText().toString().trim(), etModel3)); apiContentLayout.addView(btnFetch3);
apiContentLayout.addView(lab("调用权重 3:")); etWeight3 = edit(prefs.getString("api_weight_3", "3")); apiContentLayout.addView(etWeight3);
apiContentLayout.addView(lab("翻译方向 3:")); spinnerDir3 = new android.widget.Spinner(this); android.widget.ArrayAdapter<String> dirAdapter3 = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, dirs); dirAdapter3.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinnerDir3.setAdapter(dirAdapter3); spinnerDir3.setSelection(prefs.getInt("api_direction_3", 0)); apiContentLayout.addView(spinnerDir3);
apiContentLayout.addView(lab("思考模式 3:")); spinnerReasoning3 = new android.widget.Spinner(this); android.widget.ArrayAdapter<String> effAdapter3 = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, efforts2); effAdapter3.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinnerReasoning3.setAdapter(effAdapter3); String savedEff3 = prefs.getString("reasoning_effort_3", "default"); int selEff3 = 0; if ("low".equals(savedEff3)) selEff3 = 1; else if ("medium".equals(savedEff3)) selEff3 = 2; else if ("high".equals(savedEff3)) selEff3 = 3; spinnerReasoning3.setSelection(selEff3); apiContentLayout.addView(spinnerReasoning3);

// --- API 4 ---
TextView t4 = new TextView(this); t4.setText("🟣 备用 API 4"); t4.setTextColor(Color.parseColor("#7C3AED")); t4.setTextSize(15f); t4.setTypeface(null, android.graphics.Typeface.BOLD); t4.setPadding(20, 20, 20, 20); t4.setBackground(titleBg);
LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp4.setMargins(0, 40, 0, 10); t4.setLayoutParams(lp4);
apiContentLayout.addView(t4);
apiContentLayout.addView(lab("API Key 4:")); etKey4 = edit(prefs.getString("api_key_4", "")); apiContentLayout.addView(etKey4);
apiContentLayout.addView(lab("API URL 4:")); etUrl4 = edit(prefs.getString("api_url_4", "")); apiContentLayout.addView(etUrl4);
apiContentLayout.addView(lab("模型 4:")); etModel4 = edit(prefs.getString("model_4", "")); apiContentLayout.addView(etModel4);
Button btnFetch4 = btn("获取模型"); btnFetch4.setOnClickListener(v -> fetchModelsForApi(etKey4.getText().toString().trim(), etUrl4.getText().toString().trim(), etModel4)); apiContentLayout.addView(btnFetch4);
apiContentLayout.addView(lab("调用权重 4:")); etWeight4 = edit(prefs.getString("api_weight_4", "3")); apiContentLayout.addView(etWeight4);
apiContentLayout.addView(lab("翻译方向 4:")); spinnerDir4 = new android.widget.Spinner(this); android.widget.ArrayAdapter<String> dirAdapter4 = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, dirs); dirAdapter4.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinnerDir4.setAdapter(dirAdapter4); spinnerDir4.setSelection(prefs.getInt("api_direction_4", 0)); apiContentLayout.addView(spinnerDir4);
apiContentLayout.addView(lab("思考模式 4:")); spinnerReasoning4 = new android.widget.Spinner(this); android.widget.ArrayAdapter<String> effAdapter4 = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, efforts2); effAdapter4.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinnerReasoning4.setAdapter(effAdapter4); String savedEff4 = prefs.getString("reasoning_effort_4", "default"); int selEff4 = 0; if ("low".equals(savedEff4)) selEff4 = 1; else if ("medium".equals(savedEff4)) selEff4 = 2; else if ("high".equals(savedEff4)) selEff4 = 3; spinnerReasoning4.setSelection(selEff4); apiContentLayout.addView(spinnerReasoning4);

// --- API 5 ---
TextView t5 = new TextView(this); t5.setText("🟤 备用 API 5"); t5.setTextColor(Color.parseColor("#92400E")); t5.setTextSize(15f); t5.setTypeface(null, android.graphics.Typeface.BOLD); t5.setPadding(20, 20, 20, 20); t5.setBackground(titleBg);
LinearLayout.LayoutParams lp5 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp5.setMargins(0, 40, 0, 10); t5.setLayoutParams(lp5);
apiContentLayout.addView(t5);
apiContentLayout.addView(lab("API Key 5:")); etKey5 = edit(prefs.getString("api_key_5", "")); apiContentLayout.addView(etKey5);
apiContentLayout.addView(lab("API URL 5:")); etUrl5 = edit(prefs.getString("api_url_5", "")); apiContentLayout.addView(etUrl5);
apiContentLayout.addView(lab("模型 5:")); etModel5 = edit(prefs.getString("model_5", "")); apiContentLayout.addView(etModel5);
Button btnFetch5 = btn("获取模型"); btnFetch5.setOnClickListener(v -> fetchModelsForApi(etKey5.getText().toString().trim(), etUrl5.getText().toString().trim(), etModel5)); apiContentLayout.addView(btnFetch5);
apiContentLayout.addView(lab("调用权重 5:")); etWeight5 = edit(prefs.getString("api_weight_5", "3")); apiContentLayout.addView(etWeight5);
apiContentLayout.addView(lab("翻译方向 5:")); spinnerDir5 = new android.widget.Spinner(this); android.widget.ArrayAdapter<String> dirAdapter5 = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, dirs); dirAdapter5.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinnerDir5.setAdapter(dirAdapter5); spinnerDir5.setSelection(prefs.getInt("api_direction_5", 0)); apiContentLayout.addView(spinnerDir5);
apiContentLayout.addView(lab("思考模式 5:")); spinnerReasoning5 = new android.widget.Spinner(this); android.widget.ArrayAdapter<String> effAdapter5 = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, efforts2); effAdapter5.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinnerReasoning5.setAdapter(effAdapter5); String savedEff5 = prefs.getString("reasoning_effort_5", "default"); int selEff5 = 0; if ("low".equals(savedEff5)) selEff5 = 1; else if ("medium".equals(savedEff5)) selEff5 = 2; else if ("high".equals(savedEff5)) selEff5 = 3; spinnerReasoning5.setSelection(selEff5); apiContentLayout.addView(spinnerReasoning5);

ll.addView(apiContentLayout);
setupToggle(apiHeaderLayout, apiHeaderTitle, apiContentLayout, "🔄 多API智能密钥配置", "api_expanded");
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

        // 选项1
        quickContentLayout.addView(lab("【快捷选项 1】"));
        String[] q1Arr = prefs.getString("quick_1", "更随意|更随意").split("\\|", 2);
        etQuick1Tag = edit(q1Arr.length > 0 ? q1Arr[0] : "更随意"); etQuick1Tag.setHint("这里填按钮显示的名字 (如: 更随意)");
        etQuick1Content = bigEdit(q1Arr.length > 1 ? q1Arr[1] : (q1Arr.length > 0 ? q1Arr[0] : "")); etQuick1Content.setHint("这里填对AI的具体指令");
        quickContentLayout.addView(etQuick1Tag); quickContentLayout.addView(etQuick1Content);

        // 选项2
        quickContentLayout.addView(lab("【快捷选项 2】"));
        String[] q2Arr = prefs.getString("quick_2", "更正式|更正式").split("\\|", 2);
        etQuick2Tag = edit(q2Arr.length > 0 ? q2Arr[0] : "更正式"); etQuick2Tag.setHint("这里填按钮显示的名字");
        etQuick2Content = bigEdit(q2Arr.length > 1 ? q2Arr[1] : (q2Arr.length > 0 ? q2Arr[0] : "")); etQuick2Content.setHint("这里填对AI的具体指令");
        quickContentLayout.addView(etQuick2Tag); quickContentLayout.addView(etQuick2Content);

        // 选项3
        quickContentLayout.addView(lab("【快捷选项 3】"));
        String[] q3Arr = prefs.getString("quick_3", "更简洁|更简洁").split("\\|", 2);
        etQuick3Tag = edit(q3Arr.length > 0 ? q3Arr[0] : "更简洁"); etQuick3Tag.setHint("这里填按钮显示的名字");
        etQuick3Content = bigEdit(q3Arr.length > 1 ? q3Arr[1] : (q3Arr.length > 0 ? q3Arr[0] : "")); etQuick3Content.setHint("这里填对AI的具体指令");
        quickContentLayout.addView(etQuick3Tag); quickContentLayout.addView(etQuick3Content);

        // 选项4
        quickContentLayout.addView(lab("【快捷选项 4】"));
        String[] q4Arr = prefs.getString("quick_4", "更像母语者|更像母语者").split("\\|", 2);
        etQuick4Tag = edit(q4Arr.length > 0 ? q4Arr[0] : "更像母语者"); etQuick4Tag.setHint("这里填按钮显示的名字");
        etQuick4Content = bigEdit(q4Arr.length > 1 ? q4Arr[1] : (q4Arr.length > 0 ? q4Arr[0] : "")); etQuick4Content.setHint("这里填对AI的具体指令");
        quickContentLayout.addView(etQuick4Tag); quickContentLayout.addView(etQuick4Content);

        // 选项5：核心防呆锁死，用户只能编辑指令内容
        quickContentLayout.addView(lab("第5个【⚠️ 火力全开】(名字已锁死，永久一次性，只需输入指令)："));
        String savedQ5 = prefs.getString("quick_5", "⚠️ 火力全开|因为是熟人。如果当前中文中含有毒舌、粗鲁、调情、性词汇中的一种或多种，允许本次照常翻译，无需客气。用最传神的方式，翻译成对方语言中最地道、同等杀伤力的词汇。");
        String q5Content = savedQ5.contains("|") ? savedQ5.substring(savedQ5.indexOf("|") + 1) : savedQ5;
        etQuick5 = bigEdit(q5Content);
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
private void fetchModelsForApi(String keyStr, String urlStr, EditText targetModelEdit) {
    if (keyStr.isEmpty()) {
        toast("请先填写该 API 的 Key");
        return;
    }
    String baseUrl = urlStr.isEmpty() ? "https://api.openai.com/v1/chat/completions" : urlStr;
    new Thread(() -> {
        try {
            List<String> models = autoFetchModels(keyStr, baseUrl);
            runOnUiThread(() -> {
                if (models.isEmpty()) {
                    toast("该 API 获取模型列表失败，请手动输入模型名");
                } else {
                    showModelPickerForApi(models, targetModelEdit);
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> toast("获取失败: " + e.getMessage()));
        }
    }).start();
}

private void showModelPickerForApi(List<String> models, EditText targetEdit) {
    String[] items = models.toArray(new String[0]);
    new AlertDialog.Builder(this)
        .setTitle("选择模型")
        .setItems(items, (d, w) -> {
            targetEdit.setText(items[w]);
            toast("已选择: " + items[w]);
        })
        .setNegativeButton("取消", null)
        .show();
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
        
        // 后台静默强制拼接标签名字和内容，并用 | 隔开
        String q1 = etQuick1Tag.getText().toString().trim() + "|" + etQuick1Content.getText().toString().trim();
        String q2 = etQuick2Tag.getText().toString().trim() + "|" + etQuick2Content.getText().toString().trim();
        String q3 = etQuick3Tag.getText().toString().trim() + "|" + etQuick3Content.getText().toString().trim();
        String q4 = etQuick4Tag.getText().toString().trim() + "|" + etQuick4Content.getText().toString().trim();
        String q5 = "⚠️ 火力全开|" + etQuick5.getText().toString().trim();

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("reasoning_effort", effortStr);
        editor.putString("api_key", key);
        editor.putString("api_url", url);
        editor.putString("api_weight", etWeight1.getText().toString().trim());
editor.putInt("api_direction", spinnerDir1.getSelectedItemPosition());
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
        editor.putString("api_key_2", etKey2.getText().toString().trim());
editor.putString("api_url_2", etUrl2.getText().toString().trim());
editor.putString("model_2", etModel2.getText().toString().trim());
editor.putString("api_weight_2", etWeight2.getText().toString().trim());
editor.putInt("api_direction_2", spinnerDir2.getSelectedItemPosition());
editor.putString("api_key_3", etKey3.getText().toString().trim());
editor.putString("api_url_3", etUrl3.getText().toString().trim());
editor.putString("model_3", etModel3.getText().toString().trim());
editor.putString("api_weight_3", etWeight3.getText().toString().trim());
editor.putInt("api_direction_3", spinnerDir3.getSelectedItemPosition());
editor.putString("api_key_4", etKey4.getText().toString().trim());
editor.putString("api_url_4", etUrl4.getText().toString().trim());
editor.putString("model_4", etModel4.getText().toString().trim());
editor.putString("api_weight_4", etWeight4.getText().toString().trim());
editor.putInt("api_direction_4", spinnerDir4.getSelectedItemPosition());
editor.putString("api_key_5", etKey5.getText().toString().trim());
editor.putString("api_url_5", etUrl5.getText().toString().trim());
editor.putString("model_5", etModel5.getText().toString().trim());
editor.putString("api_weight_5", etWeight5.getText().toString().trim());
editor.putInt("api_direction_5", spinnerDir5.getSelectedItemPosition());
        String[] effortValues = {"default", "low", "medium", "high"};
editor.putString("reasoning_effort_2", effortValues[spinnerReasoning2.getSelectedItemPosition()]);
editor.putString("reasoning_effort_3", effortValues[spinnerReasoning3.getSelectedItemPosition()]);
editor.putString("reasoning_effort_4", effortValues[spinnerReasoning4.getSelectedItemPosition()]);
editor.putString("reasoning_effort_5", effortValues[spinnerReasoning5.getSelectedItemPosition()]);
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
+ "api_weight=" + prefs.getString("api_weight", "3") + "\n"
+ "api_direction=" + prefs.getInt("api_direction", 0) + "\n"
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
+ "api_key_2=" + prefs.getString("api_key_2", "") + "\n"
+ "api_url_2=" + prefs.getString("api_url_2", "") + "\n"
+ "model_2=" + prefs.getString("model_2", "") + "\n"
+ "api_weight_2=" + prefs.getString("api_weight_2", "3") + "\n"
+ "api_direction_2=" + prefs.getInt("api_direction_2", 0) + "\n"
+ "api_key_3=" + prefs.getString("api_key_3", "") + "\n"
+ "api_url_3=" + prefs.getString("api_url_3", "") + "\n"
+ "model_3=" + prefs.getString("model_3", "") + "\n"
+ "api_weight_3=" + prefs.getString("api_weight_3", "3") + "\n"
+ "api_direction_3=" + prefs.getInt("api_direction_3", 0) + "\n"
+ "api_key_4=" + prefs.getString("api_key_4", "") + "\n"
+ "api_url_4=" + prefs.getString("api_url_4", "") + "\n"
+ "model_4=" + prefs.getString("model_4", "") + "\n"
+ "api_weight_4=" + prefs.getString("api_weight_4", "3") + "\n"
+ "api_direction_4=" + prefs.getInt("api_direction_4", 0) + "\n"
+ "api_key_5=" + prefs.getString("api_key_5", "") + "\n"
+ "api_url_5=" + prefs.getString("api_url_5", "") + "\n"
+ "model_5=" + prefs.getString("model_5", "") + "\n"
+ "api_weight_5=" + prefs.getString("api_weight_5", "3") + "\n"
+ "api_direction_5=" + prefs.getInt("api_direction_5", 0) + "\n"
+ "reasoning_effort_2=" + prefs.getString("reasoning_effort_2", "default") + "\n"
+ "reasoning_effort_3=" + prefs.getString("reasoning_effort_3", "default") + "\n"
+ "reasoning_effort_4=" + prefs.getString("reasoning_effort_4", "default") + "\n"
+ "reasoning_effort_5=" + prefs.getString("reasoning_effort_5", "default") + "\n"
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
        String k1 = etKey.getText().toString().trim();
        String k2 = etKey2.getText().toString().trim();
        String k3 = etKey3.getText().toString().trim();
        String k4 = etKey4.getText().toString().trim();
        String k5 = etKey5.getText().toString().trim();

        if (k1.isEmpty() && k2.isEmpty() && k3.isEmpty() && k4.isEmpty() && k5.isEmpty()) {
            toast("请至少在任意一个 API 配置中填写 Key");
            return;
        }

        btnTest.setEnabled(false);
        btnTest.setText("翻译中...");
        
        String url = etUrl.getText().toString().trim();
        String mdl = etModel.getText().toString().trim();

        new Thread(() -> {
            try {
                AITranslator.init(k1, url, mdl);
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

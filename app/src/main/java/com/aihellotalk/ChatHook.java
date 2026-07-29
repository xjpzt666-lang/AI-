package com.aihellotalk;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ChatHook {

    private static final String TAG = "HT_AI";
    private static String currentChatId = "0";
    private static String currentPartnerName = "";
    private static int partnerLang = 1;
    private static String partnerNationality = ""; // 🆕 对方国籍
    private static final Set<String> translating = ConcurrentHashMap.newKeySet();

    // 默认回复语言（对方是中文用户时使用）
    private static final String DEFAULT_REPLY_LANG = "en";

    // 🆕 缓存 HelloTalk 工具类方法
    private static Method langCodeMethod = null;      // av.a.a(int) → 大写ISO代码
    private static Method langNameMethod = null;      // av.c.b(int) → 英文语言名
    private static Method smartTargetMethod = null;   // dy.t.b(String) → 智能目标语言

    // 🆕 国籍 → ISO语言代码映射表（小写国家名 → 小写语言代码）
    private static final Map<String, String> NATIONALITY_MAP = new HashMap<>();
    static {
        // 欧洲
        NATIONALITY_MAP.put("russia", "ru");
        NATIONALITY_MAP.put("ukraine", "uk");
        NATIONALITY_MAP.put("poland", "pl");
        NATIONALITY_MAP.put("belarus", "ru");
        NATIONALITY_MAP.put("kazakhstan", "ru");
        NATIONALITY_MAP.put("lithuania", "lt");
        NATIONALITY_MAP.put("latvia", "lv");
        NATIONALITY_MAP.put("estonia", "et");
        NATIONALITY_MAP.put("moldova", "ro");
        NATIONALITY_MAP.put("romania", "ro");
        NATIONALITY_MAP.put("bulgaria", "bg");
        NATIONALITY_MAP.put("czech", "cs");
        NATIONALITY_MAP.put("slovakia", "sk");
        NATIONALITY_MAP.put("slovenia", "sl");
        NATIONALITY_MAP.put("croatia", "hr");
        NATIONALITY_MAP.put("serbia", "sr");
        NATIONALITY_MAP.put("bosnia", "bs");
        NATIONALITY_MAP.put("hungary", "hu");
        NATIONALITY_MAP.put("greece", "el");
        NATIONALITY_MAP.put("italy", "it");
        NATIONALITY_MAP.put("spain", "es");
        NATIONALITY_MAP.put("portugal", "pt");
        NATIONALITY_MAP.put("france", "fr");
        NATIONALITY_MAP.put("germany", "de");
        NATIONALITY_MAP.put("netherlands", "nl");
        NATIONALITY_MAP.put("belgium", "nl");
        NATIONALITY_MAP.put("austria", "de");
        NATIONALITY_MAP.put("switzerland", "de");
        NATIONALITY_MAP.put("denmark", "da");
        NATIONALITY_MAP.put("finland", "fi");
        NATIONALITY_MAP.put("sweden", "sv");
        NATIONALITY_MAP.put("norway", "nn");
        NATIONALITY_MAP.put("iceland", "is");
        NATIONALITY_MAP.put("ireland", "en");
        NATIONALITY_MAP.put("england", "en");
        NATIONALITY_MAP.put("scotland", "en");
        NATIONALITY_MAP.put("wales", "en");
        NATIONALITY_MAP.put("czech republic", "cs");

        // 亚洲
        NATIONALITY_MAP.put("china", "zh");
        NATIONALITY_MAP.put("taiwan", "zh");
        NATIONALITY_MAP.put("hong kong", "zh");
        NATIONALITY_MAP.put("macau", "zh");
        NATIONALITY_MAP.put("japan", "ja");
        NATIONALITY_MAP.put("korea", "ko");
        NATIONALITY_MAP.put("south korea", "ko");
        NATIONALITY_MAP.put("north korea", "ko");
        NATIONALITY_MAP.put("thailand", "th");
        NATIONALITY_MAP.put("vietnam", "vi");
        NATIONALITY_MAP.put("indonesia", "id");
        NATIONALITY_MAP.put("malaysia", "ms");
        NATIONALITY_MAP.put("singapore", "en");
        NATIONALITY_MAP.put("philippines", "tl");
        NATIONALITY_MAP.put("india", "hi");
        NATIONALITY_MAP.put("pakistan", "ur");
        NATIONALITY_MAP.put("bangladesh", "bn");
        NATIONALITY_MAP.put("mongolia", "mn");
        NATIONALITY_MAP.put("nepal", "ne");
        NATIONALITY_MAP.put("sri lanka", "si");
        NATIONALITY_MAP.put("myanmar", "my");
        NATIONALITY_MAP.put("cambodia", "km");
        NATIONALITY_MAP.put("laos", "lo");

        // 中东
        NATIONALITY_MAP.put("iran", "fa");
        NATIONALITY_MAP.put("turkey", "tr");
        NATIONALITY_MAP.put("arabia", "ar");
        NATIONALITY_MAP.put("saudi arabia", "ar");
        NATIONALITY_MAP.put("uae", "ar");
        NATIONALITY_MAP.put("israel", "he");
        NATIONALITY_MAP.put("egypt", "ar");
        NATIONALITY_MAP.put("morocco", "ar");
        NATIONALITY_MAP.put("algeria", "ar");
        NATIONALITY_MAP.put("tunisia", "ar");
        NATIONALITY_MAP.put("jordan", "ar");
        NATIONALITY_MAP.put("lebanon", "ar");
        NATIONALITY_MAP.put("syria", "ar");
        NATIONALITY_MAP.put("iraq", "ar");
        NATIONALITY_MAP.put("afghanistan", "ps");

        // 美洲
        NATIONALITY_MAP.put("brazil", "pt");
        NATIONALITY_MAP.put("mexico", "es");
        NATIONALITY_MAP.put("argentina", "es");
        NATIONALITY_MAP.put("chile", "es");
        NATIONALITY_MAP.put("colombia", "es");
        NATIONALITY_MAP.put("peru", "es");
        NATIONALITY_MAP.put("venezuela", "es");
        NATIONALITY_MAP.put("cuba", "es");
        NATIONALITY_MAP.put("canada", "en");
        NATIONALITY_MAP.put("usa", "en");
        NATIONALITY_MAP.put("united states", "en");
        NATIONALITY_MAP.put("united kingdom", "en");

        // 非洲
        NATIONALITY_MAP.put("south africa", "af");
        NATIONALITY_MAP.put("nigeria", "yo");
        NATIONALITY_MAP.put("kenya", "sw");
        NATIONALITY_MAP.put("ethiopia", "am");
        NATIONALITY_MAP.put("tanzania", "sw");
        NATIONALITY_MAP.put("ghana", "ak");
        NATIONALITY_MAP.put("senegal", "wo");
        NATIONALITY_MAP.put("ivory coast", "fr");
        NATIONALITY_MAP.put("cameroon", "fr");
    }

    // 🆕 语言英文名关键词 → ISO代码映射
    private static final Map<String, String> LANG_NAME_MAP = new HashMap<>();
    static {
        LANG_NAME_MAP.put("chinese", "zh");
        LANG_NAME_MAP.put("english", "en");
        LANG_NAME_MAP.put("japanese", "ja");
        LANG_NAME_MAP.put("korean", "ko");
        LANG_NAME_MAP.put("spanish", "es");
        LANG_NAME_MAP.put("french", "fr");
        LANG_NAME_MAP.put("portuguese", "pt");
        LANG_NAME_MAP.put("german", "de");
        LANG_NAME_MAP.put("italian", "it");
        LANG_NAME_MAP.put("russian", "ru");
        LANG_NAME_MAP.put("arabic", "ar");
        LANG_NAME_MAP.put("turkish", "tr");
        LANG_NAME_MAP.put("polish", "pl");
        LANG_NAME_MAP.put("ukrainian", "uk");
        LANG_NAME_MAP.put("czech", "cs");
        LANG_NAME_MAP.put("slovak", "sk");
        LANG_NAME_MAP.put("hungarian", "hu");
        LANG_NAME_MAP.put("romanian", "ro");
        LANG_NAME_MAP.put("bulgarian", "bg");
        LANG_NAME_MAP.put("greek", "el");
        LANG_NAME_MAP.put("dutch", "nl");
        LANG_NAME_MAP.put("swedish", "sv");
        LANG_NAME_MAP.put("norwegian", "nn");
        LANG_NAME_MAP.put("finnish", "fi");
        LANG_NAME_MAP.put("danish", "da");
        LANG_NAME_MAP.put("hebrew", "he");
        LANG_NAME_MAP.put("thai", "th");
        LANG_NAME_MAP.put("vietnamese", "vi");
        LANG_NAME_MAP.put("indonesian", "id");
        LANG_NAME_MAP.put("malay", "ms");
        LANG_NAME_MAP.put("tagalog", "tl");
        LANG_NAME_MAP.put("hindi", "hi");
        LANG_NAME_MAP.put("bengali", "bn");
        LANG_NAME_MAP.put("urdu", "ur");
        LANG_NAME_MAP.put("persian", "fa");
        LANG_NAME_MAP.put("farsi", "fa");
        LANG_NAME_MAP.put("azerbaijani", "az");
        LANG_NAME_MAP.put("catalan", "ca");
        LANG_NAME_MAP.put("croatian", "hr");
        LANG_NAME_MAP.put("serbian", "sr");
        LANG_NAME_MAP.put("slovenian", "sl");
        LANG_NAME_MAP.put("lithuanian", "lt");
        LANG_NAME_MAP.put("latvian", "lv");
        LANG_NAME_MAP.put("estonian", "et");
        LANG_NAME_MAP.put("afrikaans", "af");
        LANG_NAME_MAP.put("tamil", "ta");
        LANG_NAME_MAP.put("telugu", "te");
        LANG_NAME_MAP.put("marathi", "mr");
        LANG_NAME_MAP.put("gujarati", "gu");
        LANG_NAME_MAP.put("punjabi", "pa");
        LANG_NAME_MAP.put("kannada", "kn");
        LANG_NAME_MAP.put("malayalam", "ml");
        LANG_NAME_MAP.put("nepali", "ne");
        LANG_NAME_MAP.put("sinhala", "si");
        LANG_NAME_MAP.put("burmese", "my");
        LANG_NAME_MAP.put("khmer", "km");
        LANG_NAME_MAP.put("lao", "lo");
        LANG_NAME_MAP.put("mongolian", "mn");
        LANG_NAME_MAP.put("georgian", "ka");
        LANG_NAME_MAP.put("armenian", "hy");
        LANG_NAME_MAP.put("amharic", "am");
        LANG_NAME_MAP.put("swahili", "sw");
        LANG_NAME_MAP.put("yoruba", "yo");
        LANG_NAME_MAP.put("zulu", "zu");
        LANG_NAME_MAP.put("xhosa", "xh");
        LANG_NAME_MAP.put("afrikaans", "af");
    }

    // ──────────────────────────────────────
    // 主安装入口
    // ──────────────────────────────────────

    public static void install(ClassLoader cl) {
        log("=== Hook v9.2 (国籍映射 + 双轨语言判定 + AI翻译) ===");

        // 缓存 av.a.a(int) — ID → ISO代码
        try {
            Class<?> avClass = XposedHelpers.findClass("av.a", cl);
            langCodeMethod = avClass.getMethod("a", int.class);
            log("✅ 缓存 av.a.a(int) 成功");
        } catch (Throwable e) {
            log("⚠️ 缓存 av.a.a(int) 失败: " + e.getMessage());
        }

        // 🆕 尝试缓存 av.c.b(int) — ID → 语言英文名
        try {
            Class<?> avcClass = XposedHelpers.findClass("av.c", cl);
            langNameMethod = avcClass.getMethod("b", int.class);
            log("✅ 缓存 av.c.b(int) 成功");
        } catch (Throwable e) {
            log("⚠️ 缓存 av.c.b(int) 失败: " + e.getMessage());
        }

        // 🆕 尝试缓存 dy.t.b(String) — 智能目标语言
        try {
            Class<?> dyClass = XposedHelpers.findClass("dy.t", cl);
            smartTargetMethod = dyClass.getMethod("b", String.class);
            log("✅ 缓存 dy.t.b(String) 成功");
        } catch (Throwable e) {
            log("⚠️ 缓存 dy.t.b(String) 失败: " + e.getMessage());
        }

        try { hookRecv(cl); } catch (Throwable e) { log("接收 ❌ " + e); }
        try { hookLang(cl); } catch (Throwable e) { log("语言 ❌ " + e); }
        try { hookBtnOld(cl); } catch (Throwable e) { log("旧版按钮 ❌ " + e); }
        try { hookBtnNew(cl); } catch (Throwable e) { log("新版按钮 ❌ " + e); }

        log("=== 完成 ===");
    }

    // ──────────────────────────────────────
    // 1. 接收消息 Hook（AI翻译）
    // ──────────────────────────────────────

    private static void hookRecv(ClassLoader cl) throws Exception {
        Class<?> hm = cl.loadClass("com.hellotalk.lib.im.entity.HTIMMessage");
        XposedBridge.hookAllMethods(hm, "getMessageContent", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object msg = p.thisObject;
                    boolean isMine = (Boolean) XposedHelpers.callMethod(msg, "isSender");
                    Object bean = p.getResult();
                    if (bean == null) return;

                    String text;
                    try {
                        text = (String) XposedHelpers.callMethod(bean, "getText");
                    } catch (Exception e) {
                        return;
                    }
                    if (text == null || text.isEmpty()) return;

                    int cidInt = 0;
                    try {
                        cidInt = (Integer) XposedHelpers.callMethod(msg, "getChatId");
                    } catch (Exception ignored) {}
                    currentChatId = String.valueOf(cidInt);

                    String senderName = null;
                    try {
                        senderName = (String) XposedHelpers.callMethod(msg, "getSenderName");
                    } catch (Exception ignored) {}
                    if (senderName != null && !senderName.isEmpty()) {
                        currentPartnerName = senderName;
                    }

                    // 注册朋友（用双轨判定语言）
                    String detectedLang = detectFriendLanguage(partnerLang, partnerNationality);
                    AITranslator.registerFriend(currentChatId, currentPartnerName, detectedLang);

                    String prefix = isMine ? "我: " : "她: ";
                    AITranslator.appendHistory(currentChatId, "user", prefix + text);

                    if (isMine) return;

                    // 日语不翻译
                    if (AITranslator.containsJapanese(text)) {
                        log("跳过翻译(日语): " + text.substring(0, Math.min(20, text.length())));
                        return;
                    }

                    // 纯中文不翻译
                    if (AITranslator.isChineseOnly(text)) {
                        return;
                    }

                    // 外语 → AI翻译成中文
                    log("🌐 检测到外语消息，准备AI翻译");

                    String mid = null;
                    try {
                        mid = (String) XposedHelpers.callMethod(msg, "getMsgId");
                    } catch (Exception ignored) {}
                    if (mid == null) {
                        mid = "n_" + text.hashCode();
                    }

                    String cached = AITranslator.getCached(mid);
                    if (cached != null) {
                        try {
                            XposedHelpers.callMethod(bean, "setText", cached);
                        } catch (Exception ignored) {}
                        return;
                    }

                    if (!translating.add(mid)) return;

                    Object finalBean = bean;
                    String finalText = text;
                    String finalMid = mid;
                    new Thread(() -> {
                        try {
                            String t = AITranslator.toChinese(finalText);
                            if (t != null && !t.equals(finalText)) {
                                AITranslator.cacheResult(finalMid, t);
                                try {
                                    XposedHelpers.callMethod(finalBean, "setText", t);
                                } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {
                        } finally {
                            translating.remove(finalMid);
                        }
                    }).start();

                } catch (Throwable ignored) {}
            }
        });
        log("✅ 接收");
    }

    // ──────────────────────────────────────
    // 2. 语言检测 Hook（双轨：国籍 + 语言ID）
    // ──────────────────────────────────────

    private static void hookLang(ClassLoader cl) throws Exception {
        Class<?> vm = XposedHelpers.findClass(
                "com.hellotalk.talk.detail.data.source.ChatDetailViewModel", cl);
        Field uf = vm.getDeclaredField("chatUser");
        uf.setAccessible(true);

        Class<?> hm = cl.loadClass("com.hellotalk.lib.im.entity.HTIMMessage");

        XposedHelpers.findAndHookMethod(vm, "generateChatMessage", hm, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object u = uf.get(p.thisObject);
                            if (u == null) return;

                            int l = (Integer) XposedHelpers.callMethod(u, "getNativeLang");

                            // 🆕 读取国籍
                            String nationality = "";
                            try {
                                nationality = (String) XposedHelpers.callMethod(u, "getNationality");
                            } catch (Exception e) {
                                log("⚠️ getNationality() 不可用: " + e.getMessage());
                            }

                            if (l != partnerLang || !nationality.equals(partnerNationality)) {
                                partnerLang = l;
                                partnerNationality = nationality != null ? nationality : "";

                                // 🆕 双轨判定语言
                                String detectedLang = detectFriendLanguage(l, partnerNationality);
                                String langName = getDynamicLangName(l);

                                log("🌍 语言切换: ID:" + l + " 国籍:" + partnerNationality
                                        + " → " + detectedLang + " (" + langName + ")");
                            }

                            String userName = null;
                            try {
                                userName = (String) XposedHelpers.callMethod(u, "getUserName");
                            } catch (Exception ignored) {}
                            String nickName = null;
                            try {
                                nickName = (String) XposedHelpers.callMethod(u, "getNickName");
                            } catch (Exception ignored) {}

                            if (userName != null && !userName.isEmpty()) {
                                currentPartnerName = userName;
                            } else if (nickName != null && !nickName.isEmpty()) {
                                currentPartnerName = nickName;
                            }

                            if (currentChatId != null && !currentChatId.equals("0")) {
                                String currentStoredLang = AITranslator.getFriendLang(currentChatId);
                                String newLang = detectFriendLanguage(l, partnerNationality);
                                if (!newLang.equals(currentStoredLang)) {
                                    AITranslator.registerFriend(
                                            currentChatId,
                                            currentPartnerName,
                                            newLang
                                    );
                                    log("👤 朋友: " + currentPartnerName + " (" + newLang + ")");
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        log("✅ 语言-双轨判定");
    }

    // ──────────────────────────────────────
    // 3 & 4. 按钮注入
    // ──────────────────────────────────────

    private static void hookBtnOld(ClassLoader cl) throws Exception {
        Class<?> boxClass = XposedHelpers.findClass(
                "com.hellotalk.chat.ui.ChatInputBoxView", cl);
        XposedBridge.hookAllConstructors(boxClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                v.postDelayed(() -> tryAddBtn_Old(v), 1200);
            }
        });
        log("✅ 旧版按钮");
    }

    private static void hookBtnNew(ClassLoader cl) throws Exception {
        Class<?> operateClass = XposedHelpers.findClass(
                "com.hellotalk.talk.detail.widget.input.ChatInputUIOperate", cl);
        XposedBridge.hookAllConstructors(operateClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                v.postDelayed(() -> tryAddBtn_New(v), 1500);
            }
        });
        log("✅ 新版按钮");
    }

    // ──────────────────────────────────────
    // tryAddBtn
    // ──────────────────────────────────────

    private static void tryAddBtn_New(View box) {
        EditText edit = findEditTextInView(box);
        if (edit != null) {
            addTranslateBtn((ViewGroup) box, edit);
        } else {
            log("⚠️ 新版按钮：未找到EditText");
        }
    }

    private static void tryAddBtn_Old(View box) {
        EditText edit = findEditTextInView(box);
        if (edit != null) {
            addTranslateBtn((ViewGroup) box, edit);
        } else {
            log("⚠️ 旧版按钮：未找到EditText");
        }
    }

    private static EditText findEditTextInView(View view) {
        try {
            Field[] fields = view.getClass().getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                Object val = field.get(view);
                if (val instanceof EditText) {
                    return (EditText) val;
                }
            }
        } catch (Exception ignored) {}

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof EditText) {
                    return (EditText) child;
                }
                EditText found = findEditTextInView(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ──────────────────────────────────────
    // addTranslateBtn
    // ──────────────────────────────────────

    private static void addTranslateBtn(ViewGroup layout, EditText edit) {
        Object tag = layout.getTag();
        if (tag != null && "HT_AI_BTN".equals(tag.toString())) return;

        Button btn = new Button(layout.getContext());
        btn.setText("译");
        btn.setTextSize(12f);
        btn.setAllCaps(false);
        btn.setPadding(12, 4, 12, 4);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#CC333333"));
        bg.setCornerRadius(8f);
        btn.setBackground(bg);
        btn.setTextColor(Color.parseColor("#FFFFFFFF"));
        btn.setAlpha(0.93f);

        btn.setVisibility(View.GONE);

        layout.addView(btn, 0);
        layout.setTag("HT_AI_BTN");

        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (s != null && !s.toString().trim().isEmpty()
                        && AITranslator.isChineseOnly(s.toString())) {
                    btn.setVisibility(View.VISIBLE);
                    btn.setEnabled(true);
                    btn.setText("译");
                    btn.setAlpha(0.94f);
                } else {
                    btn.setVisibility(View.GONE);
                }
            }
        });

        btn.setOnClickListener(v -> {
            String text = edit.getText().toString().trim();
            if (text.isEmpty() || !AITranslator.isChineseOnly(text)) {
                btn.setVisibility(View.GONE);
                return;
            }

            btn.setEnabled(false);
            btn.setText("...");
            btn.setAlpha(1.0f);

            new Thread(() -> {
                try {
                    String targetLang = determineSmartTargetLang();
                    log("🔄 AI翻译请求: 朋友=" + AITranslator.getFriendName(currentChatId)
                            + " 目标语言=" + targetLang
                            + " 国籍=" + partnerNationality);

                    String result = AITranslator.translateWithHistory(
                            text, targetLang, currentChatId);

                    AITranslator.appendHistory(currentChatId, "assistant", result);
                    List<String[]> history = AITranslator.loadHistoryForDisplay(currentChatId);

                    edit.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.94f);
                        showPicker(edit, result, history);
                    });
                } catch (Exception e) {
                    edit.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.93f);
                        String errMsg = e.getMessage();
                        log("❌ AI翻译失败: " + errMsg);
                        Toast.makeText(
                                edit.getContext(),
                                "翻译失败: " + errMsg,
                                Toast.LENGTH_LONG
                        ).show();
                    });
                }
            }).start();
        });
    }

    // ──────────────────────────────────────
    // 🆕 双轨语言判定
    // ──────────────────────────────────────

    /**
     * 双轨判定对方语言：
     * 轨1：国籍映射表（最准确）
     * 轨2：av.c.b(int) 语言英文名
     * 轨3：av.a.a(int) ISO代码（回退）
     * 轨4：HelloTalk 智能判定 dy.t.b()（最终回退）
     */
    private static String detectFriendLanguage(int langId, String nationality) {
        // 轨1：国籍映射
        if (nationality != null && !nationality.isEmpty()) {
            String lower = nationality.toLowerCase().trim();
            String fromMap = NATIONALITY_MAP.get(lower);
            if (fromMap != null) {
                log("🎯 国籍判定: " + nationality + " → " + fromMap);
                return fromMap;
            }
            // 部分匹配（处理 "russian federation" 等变体）
            for (Map.Entry<String, String> entry : NATIONALITY_MAP.entrySet()) {
                if (lower.contains(entry.getKey())) {
                    log("🎯 国籍模糊判定: " + nationality + " → " + entry.getValue());
                    return entry.getValue();
                }
            }
        }

        // 轨2：语言英文名
        if (langNameMethod != null) {
            try {
                String langName = (String) langNameMethod.invoke(null, langId);
                if (langName != null) {
                    String lower = langName.toLowerCase();
                    for (Map.Entry<String, String> entry : LANG_NAME_MAP.entrySet()) {
                        if (lower.contains(entry.getKey())) {
                            log("🎯 语言名判定: " + langName + " → " + entry.getValue());
                            return entry.getValue();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 轨3：av.a.a(int) ISO代码
        String isoCode = getDynamicLangCode(langId);
        if (isoCode != null && !isoCode.equals("en")) {
            log("🎯 ISO代码判定: ID:" + langId + " → " + isoCode);
            return isoCode;
        }

        // 轨4：dy.t.b() 智能判定
        if (smartTargetMethod != null) {
            try {
                String smart = (String) smartTargetMethod.invoke(null, "EN");
                if (smart != null && !smart.isEmpty()) {
                    log("🎯 HelloTalk智能判定 → " + smart);
                    return smart.toLowerCase();
                }
            } catch (Exception ignored) {}
        }

        // 最终兜底
        log("⚠️ 无法判定语言，使用默认: " + DEFAULT_REPLY_LANG);
        return DEFAULT_REPLY_LANG;
    }

    /**
     * 智能跟随：点"译"按钮时的目标语言
     */
    private static String determineSmartTargetLang() {
        String friendLang = AITranslator.getFriendLang(currentChatId);
        log("当前朋友语言: " + friendLang + " 国籍: " + partnerNationality);

        // 重新检测（确保最新）
        String detected = detectFriendLanguage(partnerLang, partnerNationality);
        if (detected != null && !detected.isEmpty()) {
            friendLang = detected;
        }

        // 中文用户 → 默认英语
        if (friendLang != null && (friendLang.equalsIgnoreCase("zh")
                || friendLang.equalsIgnoreCase("cn")
                || friendLang.startsWith("zh"))) {
            log("中文用户 → 使用默认回复语言: " + DEFAULT_REPLY_LANG);
            return DEFAULT_REPLY_LANG;
        }

        // 外语用户 → 跟随
        if (friendLang != null && !friendLang.isEmpty()) {
            log("外语用户 → 智能跟随: " + friendLang);
            return friendLang;
        }

        return DEFAULT_REPLY_LANG;
    }

    // ──────────────────────────────────────
    // showPicker
    // ──────────────────────────────────────

    private static void showPicker(EditText edit, String result, List<String[]> history) {
        List<String> versions = new ArrayList<>();
        Pattern p = Pattern.compile("^(\\d)[\\.\\)\\s]+(.+)$");

        String[] lines = result.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Matcher m = p.matcher(line);
            if (m.find()) {
                String content = m.group(2);
                content = content.replace("（", "").replace("）", "")
                        .replace("【", "").replace("】", "");
                versions.add(content.trim());
            }
        }
        if (versions.isEmpty()) {
            versions.add(result);
        }

        String[] items = versions.toArray(new String[0]);

        new AlertDialog.Builder(edit.getContext())
                .setTitle("选版本(" + items.length + "个) - " + AITranslator.getFriendName(currentChatId))
                .setItems(items, (dialog, which) -> {
                    edit.setText(items[which]);
                    edit.setSelection(items[which].length());
                    dialog.dismiss();
                    edit.post(() -> edit.setText(edit.getText().toString()));
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    edit.post(() -> edit.setText(edit.getText().toString()));
                })
                .show();
    }

    // ──────────────────────────────────────
    // 动态语言映射（av.a.a）
    // ──────────────────────────────────────

    private static String getDynamicLangCode(int langId) {
        if (langCodeMethod != null) {
            try {
                String code = (String) langCodeMethod.invoke(null, langId);
                return code != null ? code.toLowerCase() : "en";
            } catch (Exception e) {
                log("⚠️ av.a.a(int) 调用失败: " + e.getMessage());
            }
        }
        return "en";
    }

    private static String getDynamicLangName(int langId) {
        if (langNameMethod != null) {
            try {
                return (String) langNameMethod.invoke(null, langId);
            } catch (Exception e) {
                log("⚠️ av.c.b(int) 调用失败: " + e.getMessage());
            }
        }
        return "Unknown";
    }

    // ──────────────────────────────────────
    // 工具
    // ──────────────────────────────────────

    private static void log(String msg) {
        XposedBridge.log("HT_AI " + msg);
    }
}

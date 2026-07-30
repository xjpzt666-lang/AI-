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
import java.util.List;
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
    private static final Set<String> translating = ConcurrentHashMap.newKeySet();

    // 默认回复语言（对方是中文用户时使用）
    private static final String DEFAULT_REPLY_LANG = "en";

    // 缓存 HelloTalk 的 av.a 类方法（语言映射）
    private static Method langCodeMethod = null;      // av.a.a(int) → 大写ISO代码
    private static Method langNameMethod = null;      // av.a.b(int) → 英文语言名

    // 最新从 chatUser 获取的信息（用于打开页面时立即识别）
    private static volatile String latestNationality = "";
    private static volatile int latestNativeLang = 1;
    private static volatile String latestPartnerName = "";

    // ──────────────────────────────────────
    // 主安装入口
    // ──────────────────────────────────────

    public static void install(ClassLoader cl) {
        log("=== Hook v9.4 (打开即识别·重试轮询) ===");

        // 缓存 av.a 类方法
        try {
            Class<?> avClass = XposedHelpers.findClass("av.a", cl);
            langCodeMethod = avClass.getMethod("a", int.class);
            langNameMethod = avClass.getMethod("b", int.class);
            log("✅ 缓存语言映射方法成功");
        } catch (Throwable e) {
            log("⚠️ 缓存语言映射方法失败: " + e.getMessage());
        }

        // Hook startChat – 每次打开聊天页面必调（含重试轮询读取chatUser）
        try { hookStartChat(cl); } catch (Throwable e) { log("startChat ❌ " + e); }

        // 保留原有的 hook
        try { hookRecv(cl); } catch (Throwable e) { log("接收 ❌ " + e); }
        try { hookLang(cl); } catch (Throwable e) { log("语言 ❌ " + e); }
        try { hookBtnOld(cl); } catch (Throwable e) { log("旧版按钮 ❌ " + e); }
        try { hookBtnNew(cl); } catch (Throwable e) { log("新版按钮 ❌ " + e); }

        log("=== 完成 ===");
    }

    // ──────────────────────────────────────
    // 🆕 Hook startChat – 同步拿到 chatId + 轮询读取 chatUser
    // ──────────────────────────────────────

    private static void hookStartChat(ClassLoader cl) throws Exception {
        XposedHelpers.findAndHookMethod(
                "com.hellotalk.talk.detail.data.source.ChatDetailViewModel",
                cl,
                "startChat",
                int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int chatId = (int) param.args[0];
                        currentChatId = String.valueOf(chatId);
                        log("📂 打开聊天 chatId=" + chatId);

                        // 在新线程中轮询读取 chatUser 字段（最多6次，每次500ms）
                        final Object vm = param.thisObject;
                        new Thread(() -> {
                            try {
                                Field f = vm.getClass().getDeclaredField("chatUser");
                                f.setAccessible(true);
                                for (int i = 0; i < 6; i++) {
                                    Object chatUser = f.get(vm);
                                    if (chatUser != null) {
                                        updateFromChatUser(chatUser);
                                        return;
                                    }
                                    Thread.sleep(500);
                                }
                                log("⚠️ chatUser 读取超时（6次）");
                            } catch (Exception e) {
                                log("⚠️ 读 chatUser 失败: " + e.getMessage());
                            }
                        }).start();
                    }
                }
        );
        log("✅ startChat + 轮询");
    }

    // 🆕 从 chatUser 更新全局变量
    private static void updateFromChatUser(Object chatUser) {
        try {
            int nativeLang = (Integer) XposedHelpers.callMethod(chatUser, "getNativeLang");
            String nationality = (String) XposedHelpers.callMethod(chatUser, "getNationality");
            String nickName = (String) XposedHelpers.callMethod(chatUser, "getNickName");
            String userName = (String) XposedHelpers.callMethod(chatUser, "getUserName");

            // 更新全局变量
            latestNativeLang = nativeLang;
            latestNationality = nationality != null ? nationality : "";
            latestPartnerName = (nickName != null && !nickName.isEmpty()) ? nickName :
                    (userName != null ? userName : "");

            // 更新当前伙伴名
            if (!latestPartnerName.isEmpty()) {
                currentPartnerName = latestPartnerName;
            }

            // 立即注册朋友（即使没有消息往来）
            String langCode = getDynamicLangCode(nativeLang);
            AITranslator.registerFriend(currentChatId, currentPartnerName, langCode);
            log("👤 注册朋友: " + currentPartnerName + " (" + langCode + ") 国籍=" + latestNationality);

            // 日志输出详细信息
            String langName = getDynamicLangName(nativeLang);
            log("🌍 用户资料: ID=" + nativeLang + " → " + langCode + " (" + langName + ") 国籍=" + latestNationality);
        } catch (Exception e) {
            log("⚠️ 更新用户资料失败: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────
    // 1. 接收消息 Hook（AI翻译，逻辑不变）
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

                    // 使用动态语言映射（更新朋友信息）
                    String langCode = getDynamicLangCode(partnerLang);
                    AITranslator.registerFriend(currentChatId, currentPartnerName, langCode);

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
    // 2. 语言检测 Hook（保留，作为消息到来时的补充）
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
                            if (l != partnerLang) {
                                partnerLang = l;
                                String code = getDynamicLangCode(l);
                                String name = getDynamicLangName(l);
                                log("🌍 语言切换(消息): ID:" + l + " → " + code + " (" + name + ")");
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        log("✅ 语言-消息补充");
    }

    // ──────────────────────────────────────
    // 3 & 4. 按钮注入（不变）
    // ──────────────────────────────────────

    private static void hookBtnOld(ClassLoader cl) throws Exception {
        Class<?> boxClass = XposedHelpers.findClass(
                "com.hellotalk.chat.ui.ChatInputBoxView", cl);
        XposedBridge.hookAllConstructors(boxClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                v.postDelayed(() -> tryAddBtn_Old(v), 2000);
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
                v.postDelayed(() -> tryAddBtn_New(v), 2500);
            }
        });
        log("✅ 新版按钮");
    }

    // ──────────────────────────────────────
    // tryAddBtn（增强查找逻辑）
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
    // addTranslateBtn（按钮注入）
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
        btn.setAlpha(0.95f);

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
                    btn.setAlpha(0.93f);
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
                    // 使用最新识别的目标语言
                    String targetLang = determineSmartTargetLang();
                    log("🔄 AI翻译请求: 朋友=" + AITranslator.getFriendName(currentChatId)
                            + " 目标语言=" + targetLang + " 国籍=" + latestNationality);

                    String result = AITranslator.translateWithHistory(
                            text, targetLang, currentChatId);

                    AITranslator.appendHistory(currentChatId, "assistant", result);
                    List<String[]> history = AITranslator.loadHistoryForDisplay(currentChatId);

                    edit.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.92f);
                        showPicker(edit, result, history);
                    });
                } catch (Exception e) {
                    edit.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.88f);
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
    // 智能跟随：优先使用最新国籍/语言信息
    // ──────────────────────────────────────

    private static String determineSmartTargetLang() {
        // 1. 优先使用最新国籍映射
        String nationality = latestNationality.toLowerCase();
        if (!nationality.isEmpty()) {
            String mappedLang = mapNationalityToLang(nationality);
            if (mappedLang != null) {
                log("🎯 国籍判定: " + nationality + " → " + mappedLang);
                return mappedLang;
            }
        }

        // 2. 其次使用最新母语ID
        int nativeLang = latestNativeLang;
        String langCode = getDynamicLangCode(nativeLang);
        String langName = getDynamicLangName(nativeLang);
        if (langName != null && langName.contains("Chinese")) {
            log("🎯 母语判定: 中文 → " + DEFAULT_REPLY_LANG);
            return DEFAULT_REPLY_LANG;
        }
        if (langCode != null && !langCode.isEmpty() && !"en".equals(langCode)) {
            log("🎯 母语判定: " + langCode);
            return langCode;
        }

        // 3. 回退到已存储的朋友语言
        String friendLang = AITranslator.getFriendLang(currentChatId);
        if (friendLang != null && !friendLang.isEmpty()) {
            if (friendLang.equalsIgnoreCase("zh") || friendLang.equalsIgnoreCase("cn")
                    || friendLang.startsWith("zh")) {
                log("🎯 存储语言判定: 中文 → " + DEFAULT_REPLY_LANG);
                return DEFAULT_REPLY_LANG;
            }
            log("🎯 存储语言判定: " + friendLang);
            return friendLang;
        }

        // 4. 最终兜底
        log("🎯 兜底: " + DEFAULT_REPLY_LANG);
        return DEFAULT_REPLY_LANG;
    }

    // 国籍 → 语言代码映射表
    private static String mapNationalityToLang(String nationality) {
        if (nationality == null || nationality.isEmpty()) return null;
        switch (nationality) {
            case "china": case "taiwan": case "hong kong": case "macau":
                return "zh";
            case "russia": case "belarus": case "kazakhstan": case "kyrgyzstan":
                return "ru";
            case "ukraine":
                return "uk";
            case "poland":
                return "pl";
            case "japan":
                return "ja";
            case "korea": case "south korea":
                return "ko";
            case "vietnam":
                return "vi";
            case "thailand":
                return "th";
            case "france":
                return "fr";
            case "germany":
                return "de";
            case "spain":
                return "es";
            case "italy":
                return "it";
            case "portugal": case "brazil":
                return "pt";
            case "netherlands":
                return "nl";
            case "turkey":
                return "tr";
            case "indonesia":
                return "id";
            case "malaysia":
                return "ms";
            case "india":
                return "hi";
            case "arabia": case "saudi arabia": case "egypt": case "uae": case "qatar": case "oman": case "kuwait": case "bahrain": case "jordan": case "lebanon": case "iraq": case "syria": case "yemen": case "libya": case "tunisia": case "algeria": case "morocco": case "sudan": case "palestine":
                return "ar";
            default:
                return null;
        }
    }

    // ──────────────────────────────────────
    // 弹窗优化：卡片式提取（纯净复制 + 中文提示）
    // ──────────────────────────────────────

    private static void showPicker(EditText edit, String result, List<String[]> history) {
        List<String[]> parsedItems = new ArrayList<>();
        String[] lines = result.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            // 容错处理：暴力剔除AI前缀，例如 "版本1："、"一、"、"1."、"*"、"-"
            line = line.replaceFirst("^(版本\\d*[：:\\s]*|Option\\s*\\d*[：:\\s]*|[\\*\\-\\d一二三四五]+[\\.\\)、：:\\s]*)", "").trim();
            
            // 丢弃干扰的Markdown加粗符号
            line = line.replace("**", "");
            
            if (line.isEmpty()) continue;

            // 以 | 分割，精准定位：外文 | 中文大意 | 标签
            if (line.contains("|")) {
                String[] parts = line.split("\\|");
                String foreignText = parts[0].trim();
                
                // 去除可能遗留的外文首尾多余引号等干扰符号
                foreignText = foreignText.replaceAll("^[\"“'‘]+|[\"”'’]+$", "");
                
                String chineseMean = parts.length > 1 ? parts[1].trim() : "";
                String labelText = parts.length > 2 ? parts[2].trim() : "";
                parsedItems.add(new String[]{foreignText, chineseMean, labelText});
            } else {
                // 如果AI没有按要求带有|，就直接展示整行纯净结果，不带中文标签
                String foreignText = line.replaceAll("^[\"“'‘]+|[\"”'’]+$", "");
                parsedItems.add(new String[]{foreignText, "", ""});
            }
        }

        // 兜底防御，如果什么都没匹配到，把完整的原始串塞进去
        if (parsedItems.isEmpty()) {
            parsedItems.add(new String[]{result, "", ""});
        }

        // 构建自定义UI
        android.content.Context ctx = edit.getContext();
        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        android.widget.LinearLayout container = new android.widget.LinearLayout(ctx);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(40, 20, 40, 20);
        sv.addView(container);

        String displayName = !latestPartnerName.isEmpty() ? latestPartnerName : currentPartnerName;
        
        // 预定义弹窗对象以便在点击事件中关闭
        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("选版本 - " + displayName)
                .setView(sv)
                .setNegativeButton("取消", (d, w) -> {
                    edit.post(() -> edit.setText(edit.getText().toString()));
                })
                .create();

        // 遍历生成独立卡片
        for (String[] item : parsedItems) {
            final String foreign = item[0];
            String chinese = item[1];
            String label = item[2];

            android.widget.LinearLayout card = new android.widget.LinearLayout(ctx);
            card.setOrientation(android.widget.LinearLayout.VERTICAL);
            card.setPadding(35, 35, 35, 35);
            
            android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 10, 0, 15);
            card.setLayoutParams(params);

            // 卡片背景与边框
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#F8F9FA")); 
            bg.setCornerRadius(16f);
            bg.setStroke(2, Color.parseColor("#E9ECEF")); 
            card.setBackground(bg);

            // 1. 上层：可插入的外文文本
            android.widget.TextView tvForeign = new android.widget.TextView(ctx);
            tvForeign.setText(foreign);
            tvForeign.setTextColor(Color.parseColor("#212529"));
            tvForeign.setTextSize(16f);
            tvForeign.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(tvForeign);

            // 2. 下层：不可插入的中文提示与标签
            if (!chinese.isEmpty() || !label.isEmpty()) {
                android.widget.TextView tvChinese = new android.widget.TextView(ctx);
                String subText = chinese;
                if (!label.isEmpty()) subText += " [" + label + "]";
                tvChinese.setText(subText);
                tvChinese.setTextColor(Color.parseColor("#6C757D"));
                tvChinese.setTextSize(13f);
                tvChinese.setPadding(0, 15, 0, 0);
                card.addView(tvChinese);
            }

            // 核心逻辑：点击卡片后只抓取 foreign 塞进输入框
            card.setOnClickListener(v -> {
                edit.setText(foreign);
                edit.setSelection(foreign.length());
                dialog.dismiss();
                // 刷新UI以显示赋值后的文本
                edit.post(() -> edit.setText(edit.getText().toString()));
            });

            container.addView(card);
        }

        dialog.show();
    }

    // ──────────────────────────────────────
    // 动态语言映射
    // ──────────────────────────────────────

    private static String getDynamicLangCode(int langId) {
        if (langCodeMethod != null) {
            try {
                String code = (String) langCodeMethod.invoke(null, langId);
                return code != null ? code.toLowerCase() : "en";
            } catch (Exception e) {
                log("⚠️ 动态语言代码获取失败: " + e.getMessage());
            }
        }
        return "en";
    }

    private static String getDynamicLangName(int langId) {
        if (langNameMethod != null) {
            try {
                return (String) langNameMethod.invoke(null, langId);
            } catch (Exception e) {
                log("⚠️ 动态语言名称获取失败: " + e.getMessage());
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

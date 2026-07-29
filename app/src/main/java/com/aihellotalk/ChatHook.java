package com.aihellotalk;

import android.app.AlertDialog;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import java.lang.reflect.Field;
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

    // ──────────────────────────────────────
    // 主安装入口
    // ──────────────────────────────────────

    public static void install(ClassLoader cl) {
        log("=== Hook v8.3 (中文用户外语翻译+按钮美化) ===");

        try { hookRecv(cl); } catch (Throwable e) { log("接收 ❌ " + e); }
        try { hookLang(cl); } catch (Throwable e) { log("语言 ❌ " + e); }
        try { hookBtnOld(cl); } catch (Throwable e) { log("旧版按钮 ❌ " + e); }
        try { hookBtnNew(cl); } catch (Throwable e) { log("新版按钮 ❌ " + e); }

        log("=== 完成 ===");
    }

    // ──────────────────────────────────────
    // 1. 接收消息 Hook（核心修改）
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

                    String langCode = langCode(partnerLang);
                    AITranslator.registerFriend(currentChatId, currentPartnerName, langCode);

                    String prefix = isMine ? "我: " : "她: ";
                    AITranslator.appendHistory(currentChatId, "user", prefix + text);

                    // 自己发的 → 不翻译
                    if (isMine) return;

                    // 🆕 核心逻辑：判断是否需要翻译
                    // 1. 如果消息包含日语 → 不翻译（无论对方是谁）
                    if (AITranslator.containsJapanese(text)) {
                        log("跳过翻译(日语): " + text.substring(0, Math.min(20, text.length())));
                        return;
                    }

                    // 2. 获取对方语言
                    String friendLang = AITranslator.getFriendLang(currentChatId);
                    boolean isChineseUser = "zh".equals(friendLang);

                    // 3. 如果消息是纯中文 → 不翻译（无论对方是谁）
                    if (AITranslator.isChineseOnly(text)) {
                        // 即使是中文用户发的中文，也不翻译
                        return;
                    }

                    // 4. 走到这里说明消息是外语（非中文非日语）
                    // 无论对方是中文用户还是外语用户，都翻译成中文
                    // （中文用户发的外语也需要翻译）

                    // 获取 msgId 用于缓存
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
    // 2. 语言检测 Hook
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
                                log("🌍 语言切换: " + langName(l) + " (ID:" + l + ")");
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
                                AITranslator.registerFriend(
                                        currentChatId,
                                        currentPartnerName,
                                        langCode(l)
                                );
                                log("👤 朋友: " + currentPartnerName + " (" + langCode(l) + ")");
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        log("✅ 语言-新签名");
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
                v.postDelayed(() -> tryAddBtn_Old(v), 800);
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
                v.postDelayed(() -> tryAddBtn_New(v), 800);
            }
        });
        log("✅ 新版按钮");
    }

    // ──────────────────────────────────────
    // tryAddBtn
    // ──────────────────────────────────────

    private static void tryAddBtn_New(View box) {
        try {
            try {
                Object etObj = XposedHelpers.callMethod(box, "obtainEditView");
                if (etObj instanceof EditText) {
                    addTranslateBtn((ViewGroup) box, (EditText) etObj);
                    return;
                }
            } catch (Exception ignored) {}

            Field bf = box.getClass().getDeclaredField("binding");
            bf.setAccessible(true);
            Object binding = bf.get(box);
            Field etf = binding.getClass().getDeclaredField("etInput");
            etf.setAccessible(true);
            Object etObj = etf.get(binding);
            if (etObj instanceof EditText) {
                addTranslateBtn((ViewGroup) box, (EditText) etObj);
            }
        } catch (Exception ignored) {}
    }

    private static void tryAddBtn_Old(View box) {
        try {
            Field bf = box.getClass().getDeclaredField("B");
            bf.setAccessible(true);
            Object edit = bf.get(box);
            if (edit instanceof EditText) {
                addTranslateBtn((ViewGroup) box, (EditText) edit);
            }
        } catch (Exception ignored) {}
    }

    // ──────────────────────────────────────
    // addTranslateBtn（按钮美化 + 中文用户处理）
    // ──────────────────────────────────────

    private static void addTranslateBtn(ViewGroup layout, EditText edit) {
        Object tag = layout.getTag();
        if (tag != null && "HT_AI_BTN".equals(tag.toString())) return;

        Button btn = new Button(layout.getContext());
        btn.setText("译");
        btn.setTextSize(11f);
        btn.setAllCaps(false);
        btn.setPadding(8, 2, 8, 2);

        // 半透明样式
        btn.setBackgroundColor(Color.parseColor("#22FFFFFF"));  // 白色背景 13%
        btn.setTextColor(Color.parseColor("#99FFFFFF"));        // 白色文字 60%
        btn.setAlpha(0.6f);

        btn.setVisibility(View.GONE);

        layout.addView(btn);
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
                    btn.setAlpha(0.6f);
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
            btn.setAlpha(1.0f);  // 翻译中不透明

            new Thread(() -> {
                try {
                    // 获取朋友语言
                    String friendLang = AITranslator.getFriendLang(currentChatId);

                    // 如果朋友是中文用户 → 默认翻译成英语
                    if ("zh".equals(friendLang)) {
                        friendLang = "en";
                        log("🔄 中文用户，默认翻译成英语");
                    }
                    // 如果语言未知 → 用 partnerLang 兜底
                    if (friendLang == null || friendLang.isEmpty()) {
                        friendLang = langCode(partnerLang);
                    }

                    String result = AITranslator.translateWithHistory(
                            text, friendLang, currentChatId);

                    AITranslator.appendHistory(currentChatId, "assistant", result);
                    List<String[]> history = AITranslator.loadHistoryForDisplay(currentChatId);

                    log("🔄 翻译请求: 朋友=" + AITranslator.getFriendName(currentChatId)
                            + " 语言=" + friendLang);

                    edit.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.6f);
                        showPicker(edit, result, history);
                    });
                } catch (Exception e) {
                    edit.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.6f);
                        log("❌ 翻译失败: " + e.getMessage());
                        android.widget.Toast.makeText(
                                edit.getContext(),
                                "翻译失败: " + e.getMessage(),
                                android.widget.Toast.LENGTH_SHORT
                        ).show();
                    });
                }
            }).start();
        });
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
    // 语言映射
    // ──────────────────────────────────────

    static String langCode(int l) {
        switch (l) {
            case 1: return "en";
            case 2: return "ru";
            case 3: return "uk";
            case 4: return "vi";
            case 5: return "th";
            case 6: return "ru";
            case 7: return "ja";
            case 8: return "ko";
            case 9: return "fr";
            case 10: return "de";
            case 11: return "es";
            case 12: return "pt";
            case 13: return "it";
            case 14: return "ar";
            case 15: return "hi";
            case 16: return "id";
            case 17: return "ms";
            case 18: return "zh";
            case 19: return "tr";
            case 20: return "nl";
            default: return "en";
        }
    }

    static String langName(int l) {
        switch (l) {
            case 1: return "English";
            case 2: return "Polish";
            case 3: return "Ukrainian";
            case 4: return "Vietnamese";
            case 5: return "Thai";
            case 6: return "Russian";
            case 7: return "Japanese";
            case 8: return "Korean";
            case 9: return "French";
            case 10: return "German";
            case 11: return "Spanish";
            case 12: return "Portuguese";
            case 13: return "Italian";
            case 14: return "Arabic";
            case 15: return "Hindi";
            case 16: return "Indonesian";
            case 17: return "Malay";
            case 18: return "Chinese";
            case 19: return "Turkish";
            case 20: return "Dutch";
            default: return "Unknown(" + l + ")";
        }
    }

    // ──────────────────────────────────────
    // 工具
    // ──────────────────────────────────────

    private static void log(String msg) {
        XposedBridge.log("HT_AI " + msg);
    }

    static String sub(String s) {
        if (s == null) return "null";
        if (s.length() <= 30) return s;
        return s.substring(0, 27) + "…";
    }
}

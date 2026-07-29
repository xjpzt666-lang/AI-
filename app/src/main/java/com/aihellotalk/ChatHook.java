package com.aihellotalk;

import android.app.AlertDialog;
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
        log("=== Hook v8.1 (认人+修复版) ===");

        try { hookRecv(cl); } catch (Throwable e) { log("接收 ❌ " + e); }
        try { hookLang(cl); } catch (Throwable e) { log("语言 ❌ " + e); }
        try { hookBtnOld(cl); } catch (Throwable e) { log("旧版按钮 ❌ " + e); }
        try { hookBtnNew(cl); } catch (Throwable e) { log("新版按钮 ❌ " + e); }

        log("=== 完成 ===");
    }

    // ──────────────────────────────────────
    // 1. 接收消息 Hook
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

                    // 获取对方名字
                    String senderName = null;
                    try {
                        senderName = (String) XposedHelpers.callMethod(msg, "getSenderName");
                    } catch (Exception ignored) {}
                    if (senderName != null && !senderName.isEmpty()) {
                        currentPartnerName = senderName;
                    }

                    // 注册朋友
                    String langCode = langCode(partnerLang);
                    AITranslator.registerFriend(currentChatId, currentPartnerName, langCode);

                    // 记录历史（无论是否翻译都记录）
                    String prefix = isMine ? "我: " : "她: ";
                    AITranslator.appendHistory(currentChatId, "user", prefix + text);

                    // 自己发的 → 不翻译
                    if (isMine) return;

                    // 🆕 使用新的判断逻辑：日语/中文都不翻译
                    if (!AITranslator.needTranslateToChinese(text)) {
                        log("跳过翻译: " + text.substring(0, Math.min(20, text.length())));
                        return;
                    }

                    // 获取 msgId 用于缓存
                    String mid = null;
                    try {
                        mid = (String) XposedHelpers.callMethod(msg, "getMsgId");
                    } catch (Exception ignored) {}
                    if (mid == null) {
                        mid = "n_" + text.hashCode();
                    }

                    // 查缓存
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
    // addTranslateBtn — 修复按钮卡死
    // ──────────────────────────────────────

    private static void addTranslateBtn(ViewGroup layout, EditText edit) {
        Object tag = layout.getTag();
        if (tag != null && "HT_AI_BTN".equals(tag.toString())) return;

        Button btn = new Button(layout.getContext());
        btn.setText("译");
        btn.setTextSize(12f);
        btn.setAllCaps(false);
        btn.setPadding(10, 3, 10, 3);
        btn.setVisibility(View.GONE);

        layout.addView(btn);
        layout.setTag("HT_AI_BTN");

        // TextWatcher：仅中文输入时显示按钮
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                // 只有输入不为空且包含中文时才显示按钮
                if (s != null && !s.toString().trim().isEmpty()
                        && AITranslator.isChineseOnly(s.toString())) {
                    btn.setVisibility(View.VISIBLE);
                    btn.setEnabled(true);  // 🆕 确保按钮可点
                    btn.setText("译");      // 🆕 重置按钮文字
                } else {
                    btn.setVisibility(View.GONE);
                }
            }
        });

        // 点击事件
        btn.setOnClickListener(v -> {
            String text = edit.getText().toString().trim();
            if (text.isEmpty() || !AITranslator.isChineseOnly(text)) {
                btn.setVisibility(View.GONE);
                return;
            }

            // 🆕 如果当前朋友是中文用户，提示无需翻译
            String friendLang = AITranslator.getFriendLang(currentChatId);
            if ("zh".equals(friendLang)) {
                android.widget.Toast.makeText(
                        edit.getContext(),
                        "对方是中文用户，无需翻译",
                        android.widget.Toast.LENGTH_SHORT
                ).show();
                btn.setVisibility(View.GONE);
                return;
            }

            btn.setEnabled(false);
            btn.setText("...");

            new Thread(() -> {
                try {
                    String langCode = friendLang;
                    if (langCode == null || langCode.isEmpty() || "zh".equals(langCode)) {
                        langCode = langCode(partnerLang);
                    }
                    String result = AITranslator.translateWithHistory(
                            text, langCode, currentChatId);

                    AITranslator.appendHistory(currentChatId, "assistant", result);
                    List<String[]> history = AITranslator.loadHistoryForDisplay(currentChatId);

                    log("🔄 翻译请求: 朋友=" + AITranslator.getFriendName(currentChatId)
                            + " 语言=" + langCode);

                    edit.post(() -> {
                        // 🆕 关键修复：无论成功失败，都恢复按钮状态
                        btn.setEnabled(true);
                        btn.setText("译");
                        showPicker(edit, result, history);
                    });
                } catch (Exception e) {
                    edit.post(() -> {
                        // 🆕 异常时也恢复按钮
                        btn.setEnabled(true);
                        btn.setText("译");
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
                    // 🆕 选择后隐藏按钮
                    edit.post(() -> {
                        // 触发 TextWatcher 重新判断
                        edit.setText(edit.getText().toString());
                    });
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    // 🆕 取消时也恢复按钮
                    edit.post(() -> {
                        edit.setText(edit.getText().toString());
                    });
                })
                .show();
    }

    // ──────────────────────────────────────
    // 语言映射（补全）
    // ──────────────────────────────────────

    static String langCode(int l) {
        switch (l) {
            case 1: return "en";   // English
            case 2: return "ru";   // Polish
            case 3: return "uk";   // Ukrainian
            case 4: return "vi";   // Vietnamese
            case 5: return "th";   // Thai
            case 6: return "ru";   // Russian
            case 7: return "ja";   // Japanese
            case 8: return "ko";   // Korean
            case 9: return "fr";   // French
            case 10: return "de";  // German
            case 11: return "es";  // Spanish
            case 12: return "pt";  // Portuguese
            case 13: return "it";  // Italian
            case 14: return "ar";  // Arabic
            case 15: return "hi";  // Hindi
            case 16: return "id";  // Indonesian
            case 17: return "ms";  // Malay
            case 18: return "zh";  // ← 🆕 你日志里的 18 号语言，暂定为中文
            case 19: return "tr";  // Turkish
            case 20: return "nl";  // Dutch
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
            case 18: return "Chinese(可能)";
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

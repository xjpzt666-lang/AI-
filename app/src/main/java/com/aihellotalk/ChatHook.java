package com.aihellotalk;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.SpannableStringBuilder;
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
    private static int currentChatId;
    private static String currentPartnerName;
    private static int partnerLang;
    private static final Set<String> translating = ConcurrentHashMap.newKeySet();

    // ──────────────────────────────────────
    // 主安装入口
    // ──────────────────────────────────────

    public static void install(ClassLoader cl) {
        log("=== Hook v7.1 ===");

        try { hookRecv(cl); } catch (Throwable e) { log("接收 ❌ " + e); }
        try { hookRecvOld(cl); } catch (Throwable e) { log("接收旧版 ❌ " + e); }
        try { hookLang(cl); } catch (Throwable e) { log("语言 ❌ " + e); }
        try { hookBtnOld(cl); } catch (Throwable e) { log("旧版按钮 ❌ " + e); }
        try { hookBtnNew(cl); } catch (Throwable e) { log("新版按钮 ❌ " + e); }

        log("=== 完成 ===");
    }

    // ──────────────────────────────────────
    // 1. 接收消息 Hook（新版）
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

                    int cid = 0;
                    try {
                        cid = (Integer) XposedHelpers.callMethod(msg, "getChatId");
                    } catch (Exception ignored) {}
                    currentChatId = cid;

                    // 记录历史
                    String prefix = isMine ? "我: " : "她: ";
                    AITranslator.appendHistory(cid, "user", prefix + text);

                    // 自己发的或已是中文 → 不翻译
                    if (isMine || AITranslator.isChinese(text)) return;

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

                    // 防止重复翻译
                    if (!translating.add(mid)) return;

                    // 异步翻译
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
    // 2. 接收消息 Hook（旧版）
    // ──────────────────────────────────────

    private static void hookRecvOld(ClassLoader cl) throws Exception {
        Class<?> msgClass = XposedHelpers.findClass("com.hellotalk.chat.model.Message", cl);
        XposedBridge.hookAllMethods(msgClass, "getShowContent", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object msg = p.thisObject;
                    String c = (String) XposedHelpers.callMethod(msg, "getContent");
                    if (c == null || c.isEmpty() || AITranslator.isChinese(c)) return;

                    String mid = null;
                    try {
                        mid = (String) XposedHelpers.callMethod(msg, "getMessageid");
                    } catch (Exception ignored) {}
                    if (mid == null) mid = "o_" + c.hashCode();

                    String cached = AITranslator.getCached(mid);
                    if (cached != null) {
                        p.setResult(new SpannableStringBuilder(cached));
                        return;
                    }

                    String t = AITranslator.toChinese(c);
                    if (t != null && !t.equals(c)) {
                        AITranslator.cacheResult(mid, t);
                        p.setResult(new SpannableStringBuilder(t));
                    }
                } catch (Throwable ignored) {}
            }
        });
        log("✅ 接收-旧版");
    }

    // ──────────────────────────────────────
    // 3. 语言检测 Hook
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
                            Object msg = p.args[0];
                            boolean isSender = (Boolean) XposedHelpers.callMethod(msg, "isSender");
                            if (isSender) return;

                            Object u = uf.get(p.thisObject);
                            if (u == null) return;

                            int l = (Integer) XposedHelpers.callMethod(u, "getNativeLang");
                            if (l != partnerLang) {
                                partnerLang = l;
                                log("🌍 " + langName(l));
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        log("✅ 语言");
    }

    // ──────────────────────────────────────
    // 4 & 5. 按钮注入
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
            EditText edit = (EditText) bf.get(box);
            if (edit != null) {
                addTranslateBtn((ViewGroup) box, edit);
            }
        } catch (Exception ignored) {}
    }

    // ──────────────────────────────────────
    // addTranslateBtn
    // ──────────────────────────────────────

    private static void addTranslateBtn(ViewGroup layout, EditText edit) {
        // 去重
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
                if (s != null && !s.toString().trim().isEmpty()
                        && AITranslator.isChinese(s.toString())) {
                    btn.setVisibility(View.VISIBLE);
                } else {
                    btn.setVisibility(View.GONE);
                }
            }
        });

        // 点击翻译
        btn.setOnClickListener(v -> {
            String text = edit.getText().toString().trim();
            if (text.isEmpty() || !AITranslator.isChinese(text)) return;

            btn.setEnabled(false);
            btn.setText("...");

            new Thread(() -> {
                try {
                    String langCode = langCode(partnerLang);
                    String result = AITranslator.translateWithHistory(
                            text, langCode, currentChatId);

                    AITranslator.appendHistory(currentChatId, "assistant", result);
                    List<String[]> history = AITranslator.loadHistoryForDisplay(currentChatId);

                    edit.post(() -> showPicker(edit, result, history));
                } catch (Exception e) {
                    edit.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("译");
                    });
                }
            }).start();
        });
    }

    // ──────────────────────────────────────
    // showPicker — 多版本选择器
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
                .setTitle("选版本(" + items.length + "个)")
                .setItems(items, (dialog, which) -> {
                    edit.setText(items[which]);
                    edit.setSelection(items[which].length());
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ──────────────────────────────────────
    // 语言映射
    // ──────────────────────────────────────

    static String langCode(int l) {
        // HelloTalk 语言ID映射（按实际需要调整）
        switch (l) {
            case 1: return "en";
            case 2: return "ru";
            case 3: return "uk";
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

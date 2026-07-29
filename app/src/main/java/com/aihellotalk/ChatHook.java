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

    // 🆕 缓存 HelloTalk 的 av.a 类方法（只用于语言映射）
    private static Method langCodeMethod = null;      // av.a.a(int) → 大写ISO代码
    private static Method langNameMethod = null;      // av.a.b(int) → 英文语言名

    // ──────────────────────────────────────
    // 主安装入口
    // ──────────────────────────────────────

    public static void install(ClassLoader cl) {
        log("=== Hook v9.1 (动态语言映射 + AI翻译) ===");

        // 🆕 只缓存 av.a 类（语言映射），不缓存 dy.t（翻译仍用AI）
        try {
            Class<?> avClass = XposedHelpers.findClass("av.a", cl);
            langCodeMethod = avClass.getMethod("a", int.class);
            langNameMethod = avClass.getMethod("b", int.class);
            log("✅ 缓存语言映射方法成功");
        } catch (Throwable e) {
            log("⚠️ 缓存语言映射方法失败: " + e.getMessage());
        }

        try { hookRecv(cl); } catch (Throwable e) { log("接收 ❌ " + e); }
        try { hookLang(cl); } catch (Throwable e) { log("语言 ❌ " + e); }
        try { hookBtnOld(cl); } catch (Throwable e) { log("旧版按钮 ❌ " + e); }
        try { hookBtnNew(cl); } catch (Throwable e) { log("新版按钮 ❌ " + e); }

        log("=== 完成 ===");
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

                    // 🆕 使用动态语言映射
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
    // 2. 语言检测 Hook（优化：防重复注册）
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
                                log("🌍 语言切换: ID:" + l + " → " + code + " (" + name + ")");
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
                                String newLang = getDynamicLangCode(l);
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
        log("✅ 语言-新签名");
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
    // addTranslateBtn（按钮注入，翻译仍用AI）
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
        btn.setAlpha(0.98f);

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
                    btn.setAlpha(0.97f);
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
                    // 🆕 智能跟随：根据对方语言决定翻译目标（AI翻译）
                    String targetLang = determineSmartTargetLang();
                    log("🔄 AI翻译请求: 朋友=" + AITranslator.getFriendName(currentChatId)
                            + " 目标语言=" + targetLang);

                    // 🆕 仍然使用 AI 翻译（AITranslator.translateWithHistory）
                    String result = AITranslator.translateWithHistory(
                            text, targetLang, currentChatId);

                    AITranslator.appendHistory(currentChatId, "assistant", result);
                    List<String[]> history = AITranslator.loadHistoryForDisplay(currentChatId);

                    edit.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.97f);
                        showPicker(edit, result, history);
                    });
                } catch (Exception e) {
                    edit.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.96f);
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
    // 智能跟随：决定点"译"按钮时的目标语言
    // ──────────────────────────────────────

    private static String determineSmartTargetLang() {
        String friendLang = AITranslator.getFriendLang(currentChatId);
        log("当前朋友语言: " + friendLang);

        // 🆕 中文用户判断：用 av.a.b(int) 的英文名是否包含 "Chinese"
        // 但这里 friendLang 已经是小写ISO代码，我们直接比较 "zh" 或 "cn"
        if (friendLang != null && (friendLang.equalsIgnoreCase("zh")
                || friendLang.equalsIgnoreCase("cn")
                || friendLang.startsWith("zh"))) {
            log("中文用户 → 使用默认回复语言: " + DEFAULT_REPLY_LANG);
            return DEFAULT_REPLY_LANG;
        }

        // 外语用户 → 智能跟随对方的语言
        if (friendLang != null && !friendLang.isEmpty()) {
            log("外语用户 → 智能跟随对方语言: " + friendLang);
            return friendLang;
        }

        // 兜底
        log("无法识别语言 → 使用默认回复语言: " + DEFAULT_REPLY_LANG);
        return DEFAULT_REPLY_LANG;
    }

    // ──────────────────────────────────────
    // showPicker（不变）
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
    // 🆕 动态语言映射（调用 HelloTalk 的 av.a）
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

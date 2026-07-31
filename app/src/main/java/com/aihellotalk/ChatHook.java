package com.aihellotalk;

import android.app.AlertDialog;
import android.content.ClipData;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ChatHook {

    private static final String TAG = "HT_AI";
    private static final String DEFAULT_REPLY_LANG = "en";

    private static String currentChatId = "0";
    private static int currentChatType = 1;
    private static String currentPartnerName = "";
    private static int partnerLang = 1;

    private static volatile String latestNationality = "";
    private static volatile int latestNativeLang = 1;
    private static volatile String latestPartnerName = "";

    private static volatile boolean isTranslatingAPI = false;

    private static final Set<String> translating = ConcurrentHashMap.newKeySet();
    private static final Set<String> recordedMsgIds = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> chatRequestMap = new ConcurrentHashMap<>();
    private static final Map<String, Integer> chatRetryCountMap = new ConcurrentHashMap<>();

    private static Method langCodeMethod = null;
    private static Method langNameMethod = null;

    // 外置按钮去重：key = msgId
    private static final Set<String> attachedFlipBtns = ConcurrentHashMap.newKeySet();

    // ═══════════════════════════════════════════
    // 安装入口
    // ═══════════════════════════════════════════

    public static void install(ClassLoader cl) {
        log("=== Hook v20.0 (外置独立按钮试装版) ===");

        try {
            Class<?> avClass = XposedHelpers.findClass("av.a", cl);
            langCodeMethod = avClass.getMethod("a", int.class);
            langNameMethod = avClass.getMethod("b", int.class);
        } catch (Throwable ignored) {}

        try { hookClipboard(cl); } catch (Throwable ignored) {}
        try { hookAdapterBind(cl); } catch (Throwable ignored) {}
        try { hookStartChat(cl); } catch (Throwable ignored) {}
        try { hookRecv(cl); } catch (Throwable ignored) {}
        try { hookLang(cl); } catch (Throwable ignored) {}
        try { hookBtnOld(cl); } catch (Throwable ignored) {}
        try { hookBtnNew(cl); } catch (Throwable ignored) {}
    }

    // ═══════════════════════════════════════════
    // 剪贴板拦截：永远复制外语原文
    // ═══════════════════════════════════════════

    private static void hookClipboard(ClassLoader cl) {
        XC_MethodHook clipHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ClipData clip = (ClipData) param.args[0];
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null) {
                        String orig = AITranslator.getForeignFuzzy(text.toString());
                        if (orig != null) {
                            param.args[0] = ClipData.newPlainText(
                                    clip.getDescription() != null ? clip.getDescription().getLabel() : "HT_AI",
                                    orig
                            );
                            log("【剪贴板拦截】已替换为纯外语原文");
                        }
                    }
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(
                    "android.content.ClipboardManager",
                    cl,
                    "setPrimaryClip",
                    ClipData.class,
                    clipHook
            );
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod(
                    "android.text.ClipboardManager",
                    cl,
                    "setText",
                    CharSequence.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            CharSequence text = (CharSequence) param.args[0];
                            if (text != null) {
                                String orig = AITranslator.getForeignFuzzy(text.toString());
                                if (orig != null) {
                                    param.args[0] = orig;
                                }
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}
    }

    // ═══════════════════════════════════════════
    // 第二阶段核心：在 Adapter bind 后插入外置翻转按钮
    // ═══════════════════════════════════════════

    private static void hookAdapterBind(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "co0.a",
                    cl,
                    "G",
                    XposedHelpers.findClass("oq0.a", cl),
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object holder = param.args[0];
                            attachExternalFlipButton(holder, false); // 接收或泛用
                        }
                    }
            );
        } catch (Throwable e) {
            log("hookAdapterBind 失败: " + e.getMessage());
        }
    }

    private static void attachExternalFlipButton(Object holder, boolean forceSender) {
        try {
            if (holder == null) return;

            // 1. 拿当前消息 delegate 和 msg
            Field delegateField = holder.getClass().getSuperclass().getDeclaredField("b"); // Loq0/a.b
            delegateField.setAccessible(true);
            Object delegate = delegateField.get(holder);
            if (delegate == null) return;

            Field msgField = delegate.getClass().getDeclaredField("a"); // oo0/f.a
            msgField.setAccessible(true);
            Object msg = msgField.get(delegate);
            if (msg == null) return;

            String msgId = null;
            try {
                msgId = (String) XposedHelpers.callMethod(msg, "getMsgId");
            } catch (Throwable ignored) {}
            if (msgId == null || msgId.isEmpty()) return;

            String[] pair = AITranslator.getCached(msgId);
            if (pair == null) {
                // 发送侧 fallback：尝试用 draft fuzzy 对应
                String msgType = null;
                try { msgType = (String) XposedHelpers.callMethod(msg, "getMsgType"); } catch (Throwable ignored) {}
                if (!"text".equals(msgType)) return;
            }

            // 2. 优先寻找 Binding 里的 flContent
            FrameLayout flContent = tryGetFrameLayoutFromBinding(holder);
            if (flContent == null) return;

            // 3. 去重
            String btnTag = "HT_AI_FLIP_BTN_" + msgId;
            View oldBtn = flContent.findViewWithTag(btnTag);
            if (oldBtn != null) return;

            // 4. 判断收发方向
            boolean isMine = false;
            try { isMine = (Boolean) XposedHelpers.callMethod(msg, "isSender"); } catch (Throwable ignored) {}
            if (forceSender) isMine = true;

            // 5. 创建按钮
            TextView flipBtn = new TextView(flContent.getContext());
            flipBtn.setTag(btnTag);
            flipBtn.setText(isMine ? "🌐" : "🔄");
            flipBtn.setTextSize(14f);
            flipBtn.setTextColor(Color.parseColor("#1DA1F2"));
            flipBtn.setGravity(Gravity.CENTER);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#CCFFFFFF"));
            bg.setCornerRadius(22f);
            bg.setStroke(1, Color.parseColor("#1DA1F2"));
            flipBtn.setBackground(bg);
            flipBtn.setPadding(10, 6, 10, 6);
            flipBtn.setElevation(8f);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (isMine) {
                lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
                lp.leftMargin = -70; // 外置到左边
            } else {
                lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                lp.rightMargin = -70; // 外置到右边
            }
            flipBtn.setLayoutParams(lp);

            final boolean finalIsMine = isMine;
            final String finalMsgId = msgId;
            flipBtn.setOnClickListener(v -> {
                try {
                    toggleBubbleText(holder, finalMsgId, finalIsMine, flipBtn);
                } catch (Throwable e) {
                    log("外置按钮翻转失败: " + e.getMessage());
                }
            });

            flContent.addView(flipBtn);
            attachedFlipBtns.add(msgId);
        } catch (Throwable e) {
            log("attachExternalFlipButton 失败: " + e.getMessage());
        }
    }

    private static FrameLayout tryGetFrameLayoutFromBinding(Object holder) {
        try {
            for (Field f : holder.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(holder);
                if (val == null) continue;

                String cn = val.getClass().getName();

                // 接收消息 binding
                if ("com.hellotalk.talk.databinding.TalkItemMessageSimpleRecBinding".equals(cn)) {
                    Field fl = val.getClass().getDeclaredField("flContent");
                    fl.setAccessible(true);
                    Object flObj = fl.get(val);
                    if (flObj instanceof FrameLayout) return (FrameLayout) flObj;
                }

                // 发送消息 binding
                if ("com.hellotalk.talk.databinding.TalkItemMessageSimpleSendBinding".equals(cn)) {
                    Field fl = val.getClass().getDeclaredField("flContent");
                    fl.setAccessible(true);
                    Object flObj = fl.get(val);
                    if (flObj instanceof FrameLayout) return (FrameLayout) flObj;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ═══════════════════════════════════════════
    // 外置按钮翻转逻辑
    // ═══════════════════════════════════════════

    private static void toggleBubbleText(Object holder, String msgId, boolean isMine, TextView flipBtn) {
        try {
            TextView bubble = findBubbleTextView(holder);
            if (bubble == null) return;

            String current = bubble.getText() != null ? bubble.getText().toString() : "";
            String[] pair = AITranslator.getCached(msgId);

            if (pair != null) {
                String foreign = pair[0];
                String chinese = pair[1];

                if (isMine) {
                    // 我发的消息：默认显示外语，点击切到中文
                    if (current.equals(foreign) || current.contains(foreign)) {
                        bubble.setText(chinese);
                        flipBtn.setText("🔄");
                    } else {
                        bubble.setText(foreign);
                        flipBtn.setText("🌐");
                    }
                } else {
                    // 对方发来的消息：默认显示中文，点击切到外语
                    if (current.equals(chinese) || current.contains(chinese)) {
                        bubble.setText(foreign);
                        flipBtn.setText("🌐");
                    } else {
                        bubble.setText(chinese);
                        flipBtn.setText("🔄");
                    }
                }
            } else if (isMine) {
                // 发送侧没缓存命中，尝试 draft fuzzy
                String draft = AITranslator.getDraftFuzzy(current);
                if (draft != null) {
                    bubble.setText(draft);
                    flipBtn.setText("🔄");
                }
            }
        } catch (Throwable e) {
            log("toggleBubbleText 失败: " + e.getMessage());
        }
    }

    // 找当前 holder 内部真正显示正文的 TextView（先简单找）
    private static TextView findBubbleTextView(Object holder) {
        try {
            // 遍历 holder 所有字段，优先找 HTCompatTextView
            for (Field f : holder.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(holder);
                if (val == null) continue;

                if (val instanceof TextView &&
                        "com.hellotalk.lib.ui.text.view.HTCompatTextView".equals(val.getClass().getName())) {
                    return (TextView) val;
                }

                if (val instanceof ViewGroup) {
                    TextView found = findTextViewRecursively((ViewGroup) val);
                    if (found != null) return found;
                }
            }

            // 再去 binding 里递归找
            for (Field f : holder.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(holder);
                if (val == null) continue;

                String cn = val.getClass().getName();
                if (cn.startsWith("com.hellotalk.talk.databinding.")) {
                    for (Field bf : val.getClass().getDeclaredFields()) {
                        bf.setAccessible(true);
                        Object bv = bf.get(val);
                        if (bv instanceof ViewGroup) {
                            TextView found = findTextViewRecursively((ViewGroup) bv);
                            if (found != null) return found;
                        } else if (bv instanceof TextView &&
                                "com.hellotalk.lib.ui.text.view.HTCompatTextView".equals(bv.getClass().getName())) {
                            return (TextView) bv;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static TextView findTextViewRecursively(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView &&
                    "com.hellotalk.lib.ui.text.view.HTCompatTextView".equals(child.getClass().getName())) {
                return (TextView) child;
            }
            if (child instanceof ViewGroup) {
                TextView found = findTextViewRecursively((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════
    // 聊天打开时，记录当前 chatId / 用户资料
    // ═══════════════════════════════════════════

    private static void hookStartChat(ClassLoader cl) throws Exception {
        XposedHelpers.findAndHookMethod(
                "com.hellotalk.talk.detail.data.source.ChatDetailViewModel",
                cl,
                "startChat",
                int.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        currentChatId = String.valueOf(param.args[0]);
                        currentChatType = (int) param.args[1];

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
                            } catch (Exception ignored) {}
                        }).start();
                    }
                }
        );
    }

    private static void updateFromChatUser(Object chatUser) {
        try {
            int nativeLang = (Integer) XposedHelpers.callMethod(chatUser, "getNativeLang");
            String nationality = (String) XposedHelpers.callMethod(chatUser, "getNationality");
            String nickName = (String) XposedHelpers.callMethod(chatUser, "getNickName");
            String userName = (String) XposedHelpers.callMethod(chatUser, "getUserName");

            latestNativeLang = nativeLang;
            latestNationality = nationality != null ? nationality : "";
            latestPartnerName = (nickName != null && !nickName.isEmpty())
                    ? nickName
                    : (userName != null ? userName : "");

            if (!latestPartnerName.isEmpty()) {
                currentPartnerName = latestPartnerName;
            }
        } catch (Throwable ignored) {}
    }

    // ═══════════════════════════════════════════
    // 接收/发送消息文本拦截
    // ═══════════════════════════════════════════

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

                    int cidInt = 0;
                    try {
                        cidInt = (Integer) XposedHelpers.callMethod(msg, "getChatId");
                    } catch (Exception ignored) {}
                    final String thisChatId = String.valueOf(cidInt);
                    currentChatId = thisChatId;

                    String senderName = null;
                    try {
                        senderName = (String) XposedHelpers.callMethod(msg, "getSenderName");
                    } catch (Exception ignored) {}
                    if (senderName != null && !senderName.isEmpty() && !isMine) {
                        currentPartnerName = senderName;
                    }

                    String text = null;
                    try {
                        text = (String) XposedHelpers.callMethod(bean, "getText");
                    } catch (Exception ignored) {}

                    String msgType = null;
                    try {
                        msgType = (String) XposedHelpers.callMethod(msg, "getMsgType");
                    } catch (Exception ignored) {}

                    if (text == null || text.isEmpty()) {
                        if ("image".equals(msgType) || "photo".equals(msgType)) {
                            text = "[对方发送了一张图片]";
                        } else if ("voice".equals(msgType) || "audio".equals(msgType)) {
                            text = "[对方发送了一条语音]";
                        } else if ("video".equals(msgType)) {
                            text = "[对方发送了一段视频]";
                        } else if ("emoji".equals(msgType) || "sticker".equals(msgType)) {
                            text = "[对方发送了一个表情包]";
                        } else {
                            return;
                        }
                    }

                    String mid = null;
                    try {
                        mid = (String) XposedHelpers.callMethod(msg, "getMsgId");
                    } catch (Exception ignored) {}
                    if (mid == null || mid.isEmpty()) {
                        mid = "n_" + text.hashCode();
                    }

                    long sendTime = System.currentTimeMillis();
                    try {
                        sendTime = (Long) XposedHelpers.callMethod(msg, "getSendTime");
                    } catch (Exception ignored) {}

                    String quotedText = null;
                    try {
                        Object replyInfo = XposedHelpers.callMethod(msg, "getReplyInfo");
                        if (replyInfo != null && !isMine) {
                            String rMsgType = (String) XposedHelpers.callMethod(replyInfo, "getMsgType");
                            if ("text".equals(rMsgType)) {
                                Class<?> jsonBeanClass = XposedHelpers.findClass(
                                        "com.hellotalk.lib.im.entity.base.HTIMJsonBean", cl);
                                Object contentBean = XposedHelpers.callMethod(
                                        replyInfo, "getMessageContent", jsonBeanClass, true);
                                if (contentBean != null) {
                                    quotedText = (String) XposedHelpers.callMethod(contentBean, "getText");
                                }
                            } else if ("image".equals(rMsgType) || "photo".equals(rMsgType)) {
                                quotedText = "[图片]";
                            } else {
                                quotedText = "[" + rMsgType + "]";
                            }
                        }
                    } catch (Exception ignored) {}

                    boolean isNewMessage = recordedMsgIds.add(thisChatId + "_" + mid);
                    if (isNewMessage) {
                        if (isMine) {
                            AITranslator.appendHistory(thisChatId, mid, "assistant", text, sendTime, null);
                        } else {
                            AITranslator.appendHistory(thisChatId, mid, "user", text, sendTime, quotedText);
                        }
                    }

                    if (text.startsWith("[")) return;
                    if (AITranslator.containsJapanese(text) || AITranslator.isChineseOnly(text)) return;

                    // 我发出的消息：正文保持纯外语，不再追加 🌐
                    if (isMine) {
                        String myChineseDraft = AITranslator.getDraftFuzzy(text);
                        if (myChineseDraft != null) {
                            AITranslator.cacheResult(mid, text, myChineseDraft);
                        }
                        return;
                    }

                    // 对方发来的消息：正文保持纯中文，不再追加 🔄
                    String[] cached = AITranslator.getCached(mid);
                    if (cached != null) {
                        try {
                            XposedHelpers.callMethod(bean, "setText", cached[1]);
                        } catch (Exception ignored) {}
                        return;
                    }

                    if (!translating.add(mid)) return;

                    final String finalText = text;
                    final String finalMid = mid;
                    final Object finalBean = bean;

                    new Thread(() -> {
                        try {
                            String t = AITranslator.toChinese(finalText, thisChatId);
                            if (t != null && !t.trim().isEmpty() && !t.equals(finalText)) {
                                AITranslator.cacheResult(finalMid, finalText, t);
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
    }

    // ═══════════════════════════════════════════
    // 语言检测 Hook
    // ═══════════════════════════════════════════

    private static void hookLang(ClassLoader cl) throws Exception {
        Class<?> vm = XposedHelpers.findClass(
                "com.hellotalk.talk.detail.data.source.ChatDetailViewModel", cl);
        Field uf = vm.getDeclaredField("chatUser");
        uf.setAccessible(true);
        Class<?> hm = cl.loadClass("com.hellotalk.lib.im.entity.HTIMMessage");

        XposedHelpers.findAndHookMethod(
                vm,
                "generateChatMessage",
                hm,
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object u = uf.get(p.thisObject);
                            if (u != null) {
                                partnerLang = (Integer) XposedHelpers.callMethod(u, "getNativeLang");
                            }
                        } catch (Throwable ignored) {}
                    }
                }
        );
    }

    // ═══════════════════════════════════════════
    // 引用回复提取
    // ═══════════════════════════════════════════

    private static String getQuoteReplyText(View rootView) {
        if (rootView == null) return null;

        if (rootView instanceof TextView) {
            TextView tv = (TextView) rootView;
            try {
                String idName = tv.getResources().getResourceEntryName(tv.getId());
                if (idName != null && idName.equalsIgnoreCase("tvReplyDesc")) {
                    if (tv.getVisibility() == View.VISIBLE) {
                        return tv.getText().toString();
                    }
                }
            } catch (Exception ignored) {}
        }

        if (rootView instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) rootView;
            for (int i = 0; i < vg.getChildCount(); i++) {
                String res = getQuoteReplyText(vg.getChildAt(i));
                if (res != null) return res;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════
    // 输入框按钮注入
    // ═══════════════════════════════════════════

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
    }

    private static void tryAddBtn_New(View box) {
        EditText edit = findEditTextInView(box);
        if (edit != null) addTranslateBtn((ViewGroup) box, edit);
    }

    private static void tryAddBtn_Old(View box) {
        EditText edit = findEditTextInView(box);
        if (edit != null) addTranslateBtn((ViewGroup) box, edit);
    }

    private static EditText findEditTextInView(View view) {
        try {
            for (Field field : view.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object val = field.get(view);
                if (val instanceof EditText) return (EditText) val;
            }
        } catch (Exception ignored) {}

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof EditText) return (EditText) child;
                EditText found = findEditTextInView(child);
                if (found != null) return found;
            }
        }
        return null;
    }

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
                if (s == null) return;
                String currentText = s.toString();

                if (isTranslatingAPI && currentText.contains("@")) {
                    AITranslator.cancelOngoingTranslation();
                    String cleanText = currentText.replace("@", "");
                    edit.removeTextChangedListener(this);
                    edit.setText(cleanText);
                    edit.setSelection(cleanText.length());
                    edit.addTextChangedListener(this);
                    return;
                }

                String textWithoutAt = currentText.replace("@", "");
                if (!currentText.trim().isEmpty() && AITranslator.isChineseOnly(textWithoutAt)) {
                    if (!isTranslatingAPI) {
                        btn.setVisibility(View.VISIBLE);
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.93f);
                    }
                } else {
                    if (!isTranslatingAPI) {
                        btn.setVisibility(View.GONE);
                    }
                }
            }
        });

        btn.setOnClickListener(v -> {
            String text = edit.getText().toString().trim();
            if (text.isEmpty() || !AITranslator.isChineseOnly(text)) return;

            isTranslatingAPI = true;
            btn.setEnabled(false);
            btn.setText("...");
            btn.setAlpha(1.0f);

            String quoteText = null;
            try {
                quoteText = getQuoteReplyText(edit.getRootView());
            } catch (Exception ignored) {}

            String textToTranslate = text;

            if (quoteText != null && !quoteText.trim().isEmpty()) {
                String orig = AITranslator.getForeignFuzzy(quoteText);
                if (orig != null) {
                    quoteText = orig;
                    log("已成功锚定并还原出极净的原文 Quote 语境！");
                }
                textToTranslate = "【我要回复的对方原话】：" + quoteText.trim() + "\n【我的回复】：" + text;
            }

            final String finalTextToTranslate = textToTranslate;
            final String rawChineseInput = text;

            new Thread(() -> {
                try {
                    String targetLang = determineSmartTargetLang();

                    if (currentChatType == 1) {
                        AITranslator.registerFriend(currentChatId, currentPartnerName, targetLang);
                    }

                    String lastReq = chatRequestMap.get(currentChatId);
                    int retryCount = chatRetryCountMap.getOrDefault(currentChatId, 0);

                    boolean isRetry = finalTextToTranslate.equals(lastReq);
                    if (isRetry) {
                        retryCount++;
                        chatRetryCountMap.put(currentChatId, retryCount);
                    } else {
                        chatRequestMap.put(currentChatId, finalTextToTranslate);
                        chatRetryCountMap.put(currentChatId, 0);
                        retryCount = 0;
                    }

                    String finalPromptText = finalTextToTranslate;
                    if (isRetry) {
                        finalPromptText = finalTextToTranslate
                                + "\n\n【系统强制指令】：用户对刚才的翻译结果不满意，要求重新生成（重试第"
                                + retryCount + "次）。请彻底抛弃你脑海中默认的第一反应，使用完全不同的表达方式、词汇或句式，给出4个全新的版本！严禁与上次翻译重复！";
                    }

                    String result = AITranslator.translateWithHistory(finalPromptText, targetLang, currentChatId);

                    isTranslatingAPI = false;
                    edit.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.92f);
                        showPicker(edit, result, rawChineseInput);
                    });
                } catch (Exception e) {
                    isTranslatingAPI = false;
                    edit.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.88f);
                        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                        if (msg.contains("canceled") || msg.contains("socket closed")) {
                            Toast.makeText(edit.getContext(), "🛑 翻译已急停，可重新编辑", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(edit.getContext(), "翻译失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }).start();
        });
    }

    // ═══════════════════════════════════════════
    // 动态语言工具
    // ═══════════════════════════════════════════

    private static String getDynamicLangCode(int langId) {
        if (langCodeMethod != null) {
            try {
                return ((String) langCodeMethod.invoke(null, langId)).toLowerCase();
            } catch (Exception ignored) {}
        }
        return "en";
    }

    private static String getDynamicLangName(int langId) {
        if (langNameMethod != null) {
            try {
                return (String) langNameMethod.invoke(null, langId);
            } catch (Exception ignored) {}
        }
        return "Unknown";
    }

    // ═══════════════════════════════════════════
    // 日志
    // ═══════════════════════════════════════════

    private static void log(String msg) {
        XposedBridge.log("HT_AI " + msg);
    }
}

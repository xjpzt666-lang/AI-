package com.aihellotalk;

import android.content.ClipData;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ChatHook {

    private static final String TAG = "HT_AI";
    private static final String DEFAULT_REPLY_LANG = "en";

    private static volatile String currentChatId = "0";
    private static volatile int currentChatType = 1;
    private static volatile String currentPartnerName = "";
    private static volatile int partnerLang = 1;

    private static volatile String latestNationality = "";
    private static volatile int latestNativeLang = 1;
    private static volatile String latestPartnerName = "";

    private static volatile boolean isTranslatingAPI = false;

    private static final Set<String> translating = ConcurrentHashMap.newKeySet();
    private static final Set<String> recordedMsgIds = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, String> chatRequestMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> chatRetryCountMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> chatShortCountMap = new ConcurrentHashMap<>();

    private static final Set<String> reverseTranslatedMsgIds = ConcurrentHashMap.newKeySet();
    // ★ 新增：反译失败重试计数（失败最多再给2次机会，防止无限烧API）
    private static final ConcurrentHashMap<String, Integer> reverseRetryMap = new ConcurrentHashMap<>();

    private static Method langCodeMethod = null;

    private static final int RECENT_IMAGE_LIMIT = 3;
    private static final ConcurrentHashMap<String, String> imageUrlToPathMap = new ConcurrentHashMap<>();
    private static final List<RenderedImageInfo> recentRenderedImages = Collections.synchronizedList(new ArrayList<>());

    private static volatile String latestRenderedImagePath = null;
    private static volatile long latestRenderedImageTime = 0;

    private static volatile String currentQuotedImagePath = null;
    private static volatile boolean currentQuotedImageMissing = false;

    private static final String HT_TEXT_VIEW_CLASS = "com.hellotalk.lib.ui.text.view.HTCompatTextView";
    private static Class<?> htTextViewClass = null;

    private static class RenderedImageInfo {
        final String path, url, compressedUrl; final long ts;
        RenderedImageInfo(String p, String u, String c, long t) { path = p; url = u; compressedUrl = c; ts = t; }
    }

    private static volatile boolean msgMethodsReady = false;
    private static Method mIsSender, mGetChatId, mGetSenderName, mGetMsgType,
            mGetMsgId, mGetSendTime, mGetReplyInfo, mGetMsgContentTyped;
    private static volatile Method mBeanGetText = null;

    private static void ensureMsgMethods(Object msg) {
        if (msgMethodsReady || msg == null) return;
        try {
            Class<?> c = msg.getClass();
            mIsSender = c.getMethod("isSender"); mGetChatId = c.getMethod("getChatId");
            mGetSenderName = c.getMethod("getSenderName"); mGetMsgType = c.getMethod("getMsgType");
            mGetMsgId = c.getMethod("getMsgId"); mGetSendTime = c.getMethod("getSendTime");
            mGetReplyInfo = c.getMethod("getReplyInfo");
            mGetMsgContentTyped = c.getMethod("getMessageContent", Class.class, boolean.class);
            msgMethodsReady = true;
        } catch (Throwable ignored) {}
    }

    private static Method ensureBeanGetText(Object bean) {
        Method m = mBeanGetText; if (m != null) return m;
        if (bean == null) return null;
        try { m = bean.getClass().getMethod("getText"); mBeanGetText = m; return m; } catch (Throwable t) { return null; }
    }

    private static Object invokeQuiet(Method m, Object target, Object... args) {
        if (m == null || target == null) return null;
        try { return (args == null || args.length == 0) ? m.invoke(target) : m.invoke(target, args); }
        catch (Throwable t) { return null; }
    }

    private static final java.util.concurrent.ExecutorService historyExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "HT_AI_HistWriter"); t.setPriority(Thread.MIN_PRIORITY + 1); return t;
            });

    private static final java.util.concurrent.ExecutorService reverseTranslateExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "HT_AI_ReverseTL"); t.setPriority(Thread.MIN_PRIORITY); return t;
            });

    public static void install(ClassLoader cl) {
        log("=== Hook v5.4 修复版 ===");
        try { htTextViewClass = XposedHelpers.findClassIfExists(HT_TEXT_VIEW_CLASS, cl); } catch (Throwable ignored) {}
        try { Class<?> avClass = XposedHelpers.findClass("av.a", cl); langCodeMethod = avClass.getMethod("a", int.class); } catch (Throwable ignored) {}
        try { hookTextViewRender(cl); } catch (Throwable t) { log("render hook fail"); }
        try { hookClipboard(cl); } catch (Throwable ignored) {}
        try { hookBubbleFlip(cl); } catch (Throwable ignored) {}
        try { hookStartChat(cl); } catch (Throwable ignored) {}
        try { hookRecv(cl); } catch (Throwable ignored) {}
        try { hookLang(cl); } catch (Throwable ignored) {}
        try { hookBtnOld(cl); } catch (Throwable ignored) {}
        try { hookBtnNew(cl); } catch (Throwable ignored) {}
        try { hookUltimateStealth(cl); } catch (Throwable ignored) {}
        try { hookImageRenderLayer(cl); } catch (Throwable ignored) {}
        try { hookReplyMessageView(cl); } catch (Throwable ignored) {}
    }

    private static void log(String msg) { XposedBridge.log("HT_AI " + msg); }

    private static boolean isPureBracketQuery(String text) {
        if (text == null) return false;
        String s = text.trim();
        return (s.startsWith("(") && s.endsWith(")")) || (s.startsWith("\uff08") && s.endsWith("\uff09"));
    }

    private static String safeCallString(Object obj, String methodName) {
        if (obj == null) return null;
        try { Object r = XposedHelpers.callMethod(obj, methodName); return r == null ? null : String.valueOf(r); }
        catch (Throwable ignored) { return null; }
    }

    private static String safeNormalize(String s) {
        if (s == null) return null;
        try {
            String x = s.trim(); if (x.isEmpty()) return null;
            int q = x.indexOf('?'); if (q >= 0) x = x.substring(0, q);
            int h = x.indexOf('#'); if (h >= 0) x = x.substring(0, h);
            try { x = URLDecoder.decode(x, "UTF-8"); } catch (Throwable ignored) {}
            return x.trim();
        } catch (Throwable e) { return s; }
    }

    private static String fileNameFromUrl(String url) {
        try {
            String s = safeNormalize(url); if (s == null || s.isEmpty()) return null;
            int idx = s.lastIndexOf('/'); if (idx >= 0 && idx < s.length() - 1) return s.substring(idx + 1); return s;
        } catch (Throwable ignored) { return null; }
    }

    private static File getHelloTalkImageCacheDir() {
        try { return new File("/storage/emulated/0/Android/data/com.hellotalk/cache/hellotalk/images_/"); }
        catch (Throwable e) { return null; }
    }

    private static void putImageMapping(String key, String path) {
        if (key == null || key.trim().isEmpty() || path == null || path.trim().isEmpty()) return;
        imageUrlToPathMap.put(key, path);
    }

    private static void addRenderedImageRecord(String path, String url, String compressedUrl) {
        if (path == null || path.isEmpty()) return;
        long now = System.currentTimeMillis(); latestRenderedImagePath = path; latestRenderedImageTime = now;
        synchronized (recentRenderedImages) {
            recentRenderedImages.add(0, new RenderedImageInfo(path, url, compressedUrl, now));
            Set<String> seen = ConcurrentHashMap.newKeySet();
            List<RenderedImageInfo> dedup = new ArrayList<>();
            for (RenderedImageInfo info : recentRenderedImages) {
                if (info != null && info.path != null && seen.add(info.path)) dedup.add(info);
            }
            recentRenderedImages.clear(); recentRenderedImages.addAll(dedup);
            while (recentRenderedImages.size() > RECENT_IMAGE_LIMIT) recentRenderedImages.remove(recentRenderedImages.size() - 1);
        }
    }

    private static String bruteFindLocalImagePathFromBean(Object imageBean) {
        if (imageBean == null) return null;
        String url = safeCallString(imageBean, "getUrl"), compressedUrl = safeCallString(imageBean, "getCompressedUrl");
        String urlNorm = safeNormalize(url), compressedNorm = safeNormalize(compressedUrl);
        String urlName = fileNameFromUrl(url), compressedName = fileNameFromUrl(compressedUrl);
        File dir = getHelloTalkImageCacheDir();
        if (dir == null || !dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f == null || !f.exists() || f.length() <= 0) continue;
            String name = f.getName();
            if (urlName != null && !urlName.isEmpty() && name.contains(urlName)) return f.getAbsolutePath();
            if (compressedName != null && !compressedName.isEmpty() && name.contains(compressedName)) return f.getAbsolutePath();
        }
        synchronized (recentRenderedImages) {
            for (RenderedImageInfo info : recentRenderedImages) {
                if (info == null || info.path == null) continue;
                File f = new File(info.path); if (!f.exists() || f.length() <= 0) continue;
                String infoUrl = safeNormalize(info.url), infoCompressed = safeNormalize(info.compressedUrl);
                if (urlNorm != null && infoUrl != null && (urlNorm.equals(infoUrl) || infoUrl.contains(urlNorm) || urlNorm.contains(infoUrl))) return info.path;
                if (compressedNorm != null && infoCompressed != null && (compressedNorm.equals(infoCompressed) || infoCompressed.contains(compressedNorm) || compressedNorm.contains(infoCompressed))) return info.path;
                String infoName = f.getName();
                if (urlName != null && infoName.contains(urlName)) return info.path;
                if (compressedName != null && infoName.contains(compressedName)) return info.path;
            }
        }
        return null;
    }

    // =========================================================
    // Stealth hooks
    // =========================================================

    private static void hookUltimateStealth(ClassLoader cl) {
        try {
            Class<?> tc = XposedHelpers.findClassIfExists("com.hellotalk.talk.detail.controller.title.TalkSingleTitleController", cl);
            if (tc != null) XposedBridge.hookAllMethods(tc, "s0", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(null); }
            });
        } catch (Throwable ignored) {}
        XC_MethodHook kill = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(null); }
        };
        try {
            Class<?> za = XposedHelpers.findClassIfExists("z10.a", cl);
            if (za != null) { XposedBridge.hookAllMethods(za, "m", kill); XposedBridge.hookAllMethods(za, "c0", kill); XposedBridge.hookAllMethods(za, "f0", kill); }
            Class<?> yb = XposedHelpers.findClassIfExists("y10.b", cl);
            if (yb != null) { XposedBridge.hookAllMethods(yb, "m", kill); XposedBridge.hookAllMethods(yb, "c0", kill); XposedBridge.hookAllMethods(yb, "f0", kill); }
        } catch (Throwable ignored) {}
        try {
            Class<?> be = XposedHelpers.findClassIfExists("b20.e", cl);
            if (be != null) XposedBridge.hookAllMethods(be, "z", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args != null && p.args.length > 0 && p.args[0] != null) {
                        String n = p.args[0].getClass().getName();
                        if ("tm.a".equals(n) || "e20.c".equals(n)) p.setResult(null);
                    }
                }
            });
        } catch (Throwable ignored) {}
        try {
            Class<?> ec = XposedHelpers.findClassIfExists("e20.c", cl);
            if (ec != null) XposedBridge.hookAllMethods(ec, "f", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(new byte[0]); }
            });
        } catch (Throwable ignored) {}
    }

    // =========================================================
    // 渲染钩子 — setText 重载全覆盖
    // =========================================================

    private static void hookTextViewRender(ClassLoader cl) {
        if (htTextViewClass == null) return;
        XC_MethodHook renderLogic = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.thisObject instanceof EditText) return;
                    if (!htTextViewClass.isInstance(param.thisObject)) return;
                    CharSequence cs = (CharSequence) param.args[0]; if (cs == null) return;
                    String s = cs.toString(); if (s.isEmpty() || s.length() > 5000) return;
                    if (s.endsWith(" \uD83C\uDF10") || s.endsWith(" \uD83D\uDD04")) return;
                    String d = AITranslator.getDraftFuzzy(s);
                    if (d == null) d = AITranslator.getChineseByForeign(s);
                    if (d != null && !d.equals(s)) {
                        SpannableStringBuilder ssb = new SpannableStringBuilder(cs);
                        ssb.append(" \uD83C\uDF10"); param.args[0] = ssb;
                    }
                } catch (Throwable ignored) {}
            }
        };
        try {
            XposedHelpers.findAndHookMethod("android.widget.TextView", null, "setText",
                    CharSequence.class, TextView.BufferType.class, renderLogic);
        } catch (Throwable t) {}
        try {
            XposedHelpers.findAndHookMethod("android.widget.TextView", null, "setText",
                    CharSequence.class, renderLogic);
        } catch (Throwable t) {}
        try {
            XposedHelpers.findAndHookMethod("android.widget.TextView", null, "setText",
                    char[].class, int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (param.thisObject instanceof EditText) return;
                                if (!htTextViewClass.isInstance(param.thisObject)) return;
                                char[] chars = (char[]) param.args[0];
                                int start = (int) param.args[1], len = (int) param.args[2];
                                if (chars == null || len <= 0 || len > 5000) return;
                                String s = new String(chars, start, len);
                                if (s.endsWith(" \uD83C\uDF10") || s.endsWith(" \uD83D\uDD04")) return;
                                String d = AITranslator.getDraftFuzzy(s);
                                if (d == null) d = AITranslator.getChineseByForeign(s);
                                if (d != null && !d.equals(s)) {
                                    String ns = s + " \uD83C\uDF10";
                                    param.args[0] = ns.toCharArray();
                                    param.args[1] = 0;
                                    param.args[2] = ns.length();
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable t) {}
    }

    private static void hookClipboard(ClassLoader cl) {
        XC_MethodHook h = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                ClipData cd = (ClipData) p.args[0];
                if (cd != null && cd.getItemCount() > 0) {
                    CharSequence t = cd.getItemAt(0).getText();
                    if (t != null) {
                        String ts = t.toString();
                        if (cd.getDescription() != null && "HT_AI_Copy".equals(cd.getDescription().getLabel())) return;
                        if (!ts.endsWith(" \uD83C\uDF10") && !ts.endsWith(" \uD83D\uDD04") && !ts.matches(".*[\\u4e00-\\u9fa5]+.*")) return;
                        try {
                            String orig = AITranslator.getForeignFuzzy(ts);
                            if (orig != null && !orig.trim().isEmpty() && !orig.equals(ts))
                                p.args[0] = ClipData.newPlainText("HT_AI", orig);
                        } catch (Throwable ignored) {}
                    }
                }
            }
        };
        try { XposedHelpers.findAndHookMethod("android.content.ClipboardManager", cl, "setPrimaryClip", ClipData.class, h); }
        catch (Throwable ignored) {}
    }

    // =========================================================
    // Bubble flip
    // =========================================================

    private static void hookBubbleFlip(ClassLoader cl) throws Exception {
        XposedHelpers.findAndHookMethod(HT_TEXT_VIEW_CLASS, cl, "onTouchEvent", MotionEvent.class,
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    TextView tv = (TextView) p.thisObject; MotionEvent ev = (MotionEvent) p.args[0];
                    if (ev == null) return;
                    CharSequence cs = tv.getText(); if (cs == null) return;
                    String s = cs.toString();
                    if (!s.endsWith(" \uD83D\uDD04") && !s.endsWith(" \uD83C\uDF10")) return;
                    Layout lay = tv.getLayout(); if (lay == null) return;
                    int line = lay.getLineForVertical((int) ev.getY());
                    int off = lay.getOffsetForHorizontal(line, ev.getX());
                    if (off < s.length() - 2) return;
                    if (ev.getAction() == MotionEvent.ACTION_UP) {
                        String clean = s.substring(0, s.length() - 2).trim();
                        if (s.endsWith(" \uD83D\uDD04")) {
                            String orig = AITranslator.getForeignByChinese(clean);
                            if (orig == null) orig = AITranslator.getForeignByDraftChinese(clean);
                            if (orig != null && !orig.equals(clean)) tv.setText(orig + " \uD83C\uDF10");
                        } else {
                            String zh = AITranslator.getChineseByForeign(clean);
                            if (zh == null) zh = AITranslator.getDraftFuzzy(clean);
                            if (zh != null && !zh.equals(clean)) tv.setText(zh + " \uD83D\uDD04");
                        }
                        p.setResult(true);
                    } else if (ev.getAction() == MotionEvent.ACTION_DOWN) p.setResult(true);
                }
            });
    }

    // =========================================================
    // Start chat
    // =========================================================

    private static void hookStartChat(ClassLoader cl) throws Exception {
        XposedHelpers.findAndHookMethod("com.hellotalk.talk.detail.data.source.ChatDetailViewModel", cl,
            "startChat", int.class, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    currentChatId = String.valueOf(p.args[0]); currentChatType = (int) p.args[1];
                    latestNationality = ""; latestNativeLang = 1; latestPartnerName = ""; currentPartnerName = "";
                    currentQuotedImagePath = null; currentQuotedImageMissing = false;
                    final Object vm = p.thisObject;
                    new Thread(() -> {
                        try {
                            Field f = vm.getClass().getDeclaredField("chatUser"); f.setAccessible(true);
                            for (int i = 0; i < 6; i++) {
                                Object cu = f.get(vm); if (cu != null) { updateFromChatUser(cu); return; }
                                Thread.sleep(500);
                            }
                        } catch (Exception ignored) {}
                    }).start();
                }
            });
    }

    private static void updateFromChatUser(Object chatUser) {
        try {
            int nl = (Integer) XposedHelpers.callMethod(chatUser, "getNativeLang");
            String nat = (String) XposedHelpers.callMethod(chatUser, "getNationality");
            String nn = (String) XposedHelpers.callMethod(chatUser, "getNickName");
            String un = (String) XposedHelpers.callMethod(chatUser, "getUserName");
            latestNativeLang = nl; latestNationality = nat != null ? nat : "";
            latestPartnerName = (nn != null && !nn.isEmpty()) ? nn : (un != null ? un : "");
            if (!latestPartnerName.isEmpty()) currentPartnerName = latestPartnerName;
            if (!currentChatId.isEmpty() && !"0".equals(currentChatId)) {
                AITranslator.updateFriendNationality(currentChatId, latestNationality);
            }
            // ★ 观察日志：确认HelloTalk返回的国籍字符串格式（英文mexico还是中文墨西哥）
            log("国籍原文: [" + latestNationality + "] 母语码: " + nl);
        } catch (Throwable ignored) {}
    }

    private static void hookLang(ClassLoader cl) throws Exception {
        Class<?> vm = XposedHelpers.findClass("com.hellotalk.talk.detail.data.source.ChatDetailViewModel", cl);
        Field uf = vm.getDeclaredField("chatUser"); uf.setAccessible(true);
        Class<?> hm = cl.loadClass("com.hellotalk.lib.im.entity.HTIMMessage");
        XposedHelpers.findAndHookMethod(vm, "generateChatMessage", hm, boolean.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try { Object u = uf.get(p.thisObject); if (u != null) partnerLang = (Integer) XposedHelpers.callMethod(u, "getNativeLang"); }
                catch (Throwable ignored) {}
            }
        });
    }

    // =========================================================
    // Image hooks
    // =========================================================

    private static void hookImageRenderLayer(ClassLoader cl) {
        try {
            Class<?> imc = XposedHelpers.findClassIfExists("com.hellotalk.talk.detail.widget.msgcard.ImageMsgCard", cl);
            if (imc != null) XposedBridge.hookAllMethods(imc, "c", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    if (p.args == null || p.args.length < 2) return;
                    Object ib = p.args[0]; Object fpo = p.args[1];
                    if (!(fpo instanceof String)) return;
                    String fp = (String) fpo; File img = new File(fp);
                    if (!img.exists() || img.length() <= 0) return;
                    String url = null, cu = null;
                    try { url = (String) XposedHelpers.callMethod(ib, "getUrl"); } catch (Throwable ignored) {}
                    try { cu = (String) XposedHelpers.callMethod(ib, "getCompressedUrl"); } catch (Throwable ignored) {}
                    putImageMapping(url, fp); putImageMapping(cu, fp);
                    putImageMapping(safeNormalize(url), fp); putImageMapping(safeNormalize(cu), fp);
                    String un = fileNameFromUrl(url), cn = fileNameFromUrl(cu);
                    if (un != null) putImageMapping("fname:" + un, fp);
                    if (cn != null) putImageMapping("fname:" + cn, fp);
                    addRenderedImageRecord(fp, url, cu);
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void hookReplyMessageView(ClassLoader cl) {
        try {
            Class<?> rv = XposedHelpers.findClassIfExists("com.hellotalk.talk.detail.widget.ReplyMessageView", cl);
            if (rv != null) {
                XposedBridge.hookAllMethods(rv, "A", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Object msg = (p.args != null && p.args.length > 0) ? p.args[0] : null;
                        currentQuotedImagePath = null; currentQuotedImageMissing = false;
                        if (msg == null) return;
                        try { String mt = (String) XposedHelpers.callMethod(msg, "getMsgType");
                            if ("image".equals(mt) || "photo".equals(mt)) currentQuotedImageMissing = true; }
                        catch (Throwable ignored) {}
                    }
                });
                XposedBridge.hookAllMethods(rv, "B", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        try { if (p.args != null && p.args.length >= 2 && p.args[1] != null &&
                                "image".equals(XposedHelpers.callMethod(p.args[1], "getMsgType"))) currentQuotedImageMissing = true; }
                        catch (Throwable ignored) {}
                    }
                });
                XposedBridge.hookAllMethods(rv, "setImageMessageImage", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        if (p.args == null || p.args.length < 1) return;
                        String lp = bruteFindLocalImagePathFromBean(p.args[0]);
                        if (lp != null && new File(lp).exists()) { currentQuotedImagePath = lp; currentQuotedImageMissing = false; }
                        else { currentQuotedImagePath = null; currentQuotedImageMissing = true; }
                    }
                });
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> rh = XposedHelpers.findClassIfExists("com.hellotalk.talk.detail.widget.reply.ReplyHolderView", cl);
            if (rh != null) {
                XposedBridge.hookAllMethods(rh, "f", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        try { if (p.args != null && p.args.length >= 2 && p.args[1] != null &&
                                "image".equals(XposedHelpers.callMethod(p.args[1], "getMsgType"))) currentQuotedImageMissing = true; }
                        catch (Throwable ignored) {}
                    }
                });
                XposedBridge.hookAllMethods(rh, "setImageMessageImage", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        if (p.args == null || p.args.length < 1) return;
                        String lp = bruteFindLocalImagePathFromBean(p.args[0]);
                        if (lp != null && new File(lp).exists()) currentQuotedImagePath = lp;
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    // =========================================================
    // Receive hook
    // =========================================================

    private static void hookRecv(ClassLoader cl) throws Exception {
        Class<?> hm = cl.loadClass("com.hellotalk.lib.im.entity.HTIMMessage");
        XposedHelpers.findAndHookMethod(hm, "getMessageContent", Class.class, boolean.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object msg = p.thisObject; ensureMsgMethods(msg);
                    Object iso = invokeQuiet(mIsSender, msg);
                    if (!(iso instanceof Boolean)) return;
                    boolean isMine = (Boolean) iso;
                    Object bean = p.getResult(); if (bean == null) return;

                    String eid = "0";
                    Object cidO = invokeQuiet(mGetChatId, msg);
                    if (cidO != null) eid = String.valueOf(cidO);
                    if ("0".equals(eid) || "null".equals(eid)) eid = currentChatId;
                    final String chatId = eid;

                    String sn = null;
                    Object sno = invokeQuiet(mGetSenderName, msg);
                    if (sno != null) sn = String.valueOf(sno);
                    if (sn != null && !sn.isEmpty() && !isMine) {
                        AITranslator.registerFriend(chatId, sn, AITranslator.getFriendLang(chatId), latestNationality);
                    }

                    Method gtm = ensureBeanGetText(bean);
                    Object to = invokeQuiet(gtm, bean);
                    String text = (to != null) ? String.valueOf(to) : null;
                    Object mto = invokeQuiet(mGetMsgType, msg);
                    String mt = (mto != null) ? String.valueOf(mto) : null;

                    if (text == null || text.isEmpty()) {
                        if ("image".equals(mt) || "photo".equals(mt)) text = "[\u5bf9\u65b9\u53d1\u9001\u4e86\u4e00\u5f20\u56fe\u7247]";
                        else if ("voice".equals(mt) || "audio".equals(mt)) text = "[\u5bf9\u65b9\u53d1\u9001\u4e86\u4e00\u6761\u8bed\u97f3]";
                        else if ("video".equals(mt)) text = "[\u5bf9\u65b9\u53d1\u9001\u4e86\u4e00\u6bb5\u89c6\u9891]";
                        else if ("emoji".equals(mt) || "sticker".equals(mt)) text = "[\u5bf9\u65b9\u53d1\u9001\u4e86\u4e00\u4e2a\u8868\u60c5\u5305]";
                        else return;
                    }

                    Object mio = invokeQuiet(mGetMsgId, msg);
                    String mid = (mio != null) ? String.valueOf(mio) : null;
                    if (mid == null || mid.isEmpty()) mid = "n_" + text.hashCode();
                    long st = System.currentTimeMillis();
                    Object sto = invokeQuiet(mGetSendTime, msg);
                    if (sto instanceof Long) st = (Long) sto;

                    // ★ 双向引用提取
                    String quotedText = null;
                    try {
                        Object ri = invokeQuiet(mGetReplyInfo, msg);
                        if (ri != null) {
                            // ★ 观察日志：确认replyInfo类型是否是HTIMMessage（决定isSender是否有效）
                            log("replyInfo类型: " + ri.getClass().getName());
                            Object rIs = invokeQuiet(mIsSender, ri);
                            boolean rIm = (rIs instanceof Boolean) && (Boolean) rIs;
                            Object rmt = invokeQuiet(mGetMsgType, ri);
                            String rmtS = (rmt != null) ? String.valueOf(rmt) : null;
                            if ("text".equals(rmtS)) {
                                Class<?> jbc = XposedHelpers.findClass("com.hellotalk.lib.im.entity.base.HTIMJsonBean", cl);
                                Object cb = invokeQuiet(mGetMsgContentTyped, ri, jbc, true);
                                if (cb != null) {
                                    Method cgt = ensureBeanGetText(cb);
                                    Object qt = invokeQuiet(cgt, cb);
                                    if (qt != null) {
                                        String rq = String.valueOf(qt);
                                        if (rIm) {
                                            String mc = AITranslator.getChineseByForeign(rq);
                                            if (mc == null) mc = AITranslator.getDraftFuzzy(rq);
                                            quotedText = (mc != null) ? mc : rq;
                                        } else quotedText = rq;
                                    }
                                }
                            } else if (rmtS != null) quotedText = "[" + rmtS + "]";
                        }
                    } catch (Exception ignored) {}

                    final boolean isPureSymbol = !AITranslator.hasAnyLetterOrDigit(text);
                    boolean isNew = recordedMsgIds.add(chatId + "_" + mid);
                    if (isNew) {
                        final String fm = mid, ft = text, fq = quotedText; final long fst = st; final boolean fmn = isMine;
                        historyExecutor.execute(() -> {
                            if (fmn) AITranslator.appendHistory(chatId, fm, "assistant", ft, fst, fq);
                            else AITranslator.appendHistory(chatId, fm, "user", ft, fst, fq);
                        });
                    }

                    // ★ 纯表情/纯标点：保留原文 + 括号分析
                    if (isPureSymbol && !isMine) {
                        final String ft3 = text; final Object fb3 = bean; final String fc3 = chatId;
                        new Thread(() -> {
                            try {
                                String display = AITranslator.analyzePureSymbol(ft3, fc3);
                                if (display != null && !display.isEmpty() && !display.equals(ft3))
                                    try { XposedHelpers.callMethod(fb3, "setText", display); } catch (Exception ignored) {}
                            } catch (Exception ignored) {}
                        }).start();
                        return;
                    }

                    if (text.startsWith("[")) return;
                    if (AITranslator.containsJapanese(text) || AITranslator.isChineseOnly(text)) return;

                    if (isMine) {
                        String d = AITranslator.getDraftFuzzy(text);
                        if (d == null) d = AITranslator.getChineseByForeign(text);
                        if (d != null) {
                            AITranslator.cacheResult(mid, text, d);
                            final Object fbk = bean; final String ftk = text;
                            new Thread(() -> {
                                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                                try { XposedHelpers.callMethod(fbk, "setText", ftk); } catch (Exception ignored) {}
                            }).start();
                        } else {
                            final String ft2 = text, fc2 = chatId, fm2 = mid;
                            final Object fb2 = bean;
                            if (reverseTranslatedMsgIds.add(fm2)) reverseTranslateExecutor.execute(() -> {
                                try {
                                    String zh = AITranslator.reverseTranslateMyForeign(ft2, fc2);
                                    if (zh != null && !zh.isEmpty()) {
                                        AITranslator.cacheResult(fm2, ft2, zh);
                                        AITranslator.rememberDraft(ft2, zh);
                                        reverseRetryMap.remove(fm2);
                                        try { XposedHelpers.callMethod(fb2, "setText", ft2); } catch (Exception ignored) {}
                                    } else {
                                        // ★ 反译失败/空结果 → 最多再给2次机会，之后放弃（防止无限烧API）
                                        int rc = reverseRetryMap.getOrDefault(fm2, 0);
                                        if (rc < 2) { reverseRetryMap.put(fm2, rc + 1); reverseTranslatedMsgIds.remove(fm2); }
                                    }
                                } catch (Exception ignored) {
                                    // ★ 异常同样最多重试2次
                                    int rc = reverseRetryMap.getOrDefault(fm2, 0);
                                    if (rc < 2) { reverseRetryMap.put(fm2, rc + 1); reverseTranslatedMsgIds.remove(fm2); }
                                }
                            });
                        }
                        return;
                    }

                    String[] cached = AITranslator.getCached(mid);
                    if (cached != null) {
                        try { XposedHelpers.callMethod(bean, "setText", cached[1].replaceAll("[\\s\uD83C\uDF10\uD83D\uDD04]+$", "") + " \uD83D\uDD04"); }
                        catch (Exception ignored) {}
                        return;
                    }

                    if (!translating.add(mid)) return;
                    final String ft = text, fm = mid; final Object fb = bean;
                    new Thread(() -> {
                        try {
                            String t = null;
                            try { t = AITranslator.toChinese(ft, chatId); }
                            catch (Exception fe) {
                                String m = fe.getMessage() == null ? "" : fe.getMessage();
                                if (!m.contains("Key\u672a\u914d\u7f6e") && !m.contains("\u672a\u521d\u59cb\u5316")) {
                                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                                    t = AITranslator.toChinese(ft, chatId);
                                }
                            }
                            if (t != null && !t.trim().isEmpty() && !t.equals(ft)) {
                                AITranslator.cacheResult(fm, ft, t);
                                try { XposedHelpers.callMethod(fb, "setText", t.replaceAll("[\\s\uD83C\uDF10\uD83D\uDD04]+$", "") + " \uD83D\uDD04"); }
                                catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {} finally { translating.remove(fm); }
                    }).start();

                } catch (Throwable ignored) {}
            }
        });
    }

    // =========================================================
    // Input button
    // =========================================================

    private static void hookBtnOld(ClassLoader cl) throws Exception {
        Class<?> bc = XposedHelpers.findClass("com.hellotalk.chat.ui.ChatInputBoxView", cl);
        XposedBridge.hookAllConstructors(bc, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { ((View) p.thisObject).postDelayed(() -> tryAddBtn((View) p.thisObject), 2000); }
        });
    }

    private static void hookBtnNew(ClassLoader cl) throws Exception {
        Class<?> oc = XposedHelpers.findClass("com.hellotalk.talk.detail.widget.input.ChatInputUIOperate", cl);
        XposedBridge.hookAllConstructors(oc, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { ((View) p.thisObject).postDelayed(() -> tryAddBtn((View) p.thisObject), 2500); }
        });
    }

    private static void tryAddBtn(View box) {
        EditText edit = findEditIn(box); if (edit != null) addTranslateBtn((ViewGroup) box, edit);
    }

    private static EditText findEditIn(View v) {
        try { for (Field f : v.getClass().getDeclaredFields()) { f.setAccessible(true); Object val = f.get(v); if (val instanceof EditText) return (EditText) val; } }
        catch (Exception ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof EditText) return (EditText) c;
                EditText found = findEditIn(c); if (found != null) return found;
            }
        }
        return null;
    }

    private static String getQuoteReplyText(View root) {
        if (root == null) return null;
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            try { if ("tvReplyDesc".equalsIgnoreCase(tv.getResources().getResourceEntryName(tv.getId())) && tv.getVisibility() == View.VISIBLE) return tv.getText().toString(); }
            catch (Exception ignored) {}
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) { String r = getQuoteReplyText(vg.getChildAt(i)); if (r != null) return r; }
        }
        return null;
    }

    private static View findNativeSendBtn(ViewGroup root) {
        if (root == null) return null;
        ArrayList<View> views = new ArrayList<>(); views.add(root);
        for (int i = 0; i < views.size(); i++) {
            View cur = views.get(i);
            try { if (cur.getId() != View.NO_ID && cur.getResources().getResourceEntryName(cur.getId()).toLowerCase().contains("send")) return cur; }
            catch (Exception ignored) {}
            if (cur instanceof ViewGroup) { ViewGroup vg = (ViewGroup) cur; for (int j = 0; j < vg.getChildCount(); j++) views.add(vg.getChildAt(j)); }
        }
        return null;
    }

    private static void addTranslateBtn(ViewGroup layout, EditText edit) {
        try { edit.setLongClickable(true); edit.setTextIsSelectable(true); edit.setFocusable(true); edit.setFocusableInTouchMode(true); }
        catch (Throwable ignored) {}
        if ("HT_AI_BTN".equals(String.valueOf(layout.getTag()))) return;

        Button btn = new Button(layout.getContext());
        btn.setText("\u8bd1"); btn.setTextSize(12f); btn.setAllCaps(false); btn.setPadding(12, 4, 12, 4);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.parseColor("#CC333333")); bg.setCornerRadius(8f);
        btn.setBackground(bg); btn.setTextColor(Color.parseColor("#FFFFFFFF")); btn.setAlpha(0.95f);
        btn.setVisibility(View.GONE); layout.addView(btn, 0); layout.setTag("HT_AI_BTN");

        final View[] nsb = new View[1];
        Runnable ev = new Runnable() {
            @Override public void run() {
                if (nsb[0] == null) nsb[0] = findNativeSendBtn(layout);
                String ct = edit.getText().toString().replace("@", "");
                if (!ct.trim().isEmpty() && AITranslator.isChineseOnly(ct)) {
                    if (!isTranslatingAPI) { btn.setVisibility(View.VISIBLE); btn.setEnabled(true); btn.setText("\u8bd1"); btn.setAlpha(0.93f); }
                    if (nsb[0] != null) nsb[0].setVisibility(View.GONE);
                } else {
                    if (!isTranslatingAPI) btn.setVisibility(View.GONE);
                    if (nsb[0] != null && !ct.trim().isEmpty()) nsb[0].setVisibility(View.VISIBLE);
                }
            }
        };

        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) { edit.post(ev); }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (s != null && isTranslatingAPI && s.toString().contains("@")) {
                    AITranslator.cancelOngoingTranslation();
                    String cl = s.toString().replace("@", "");
                    edit.removeTextChangedListener(this); edit.setText(cl); edit.setSelection(cl.length()); edit.addTextChangedListener(this);
                }
            }
        });
        edit.postDelayed(ev, 100); edit.postDelayed(ev, 500);

        btn.setOnClickListener(v -> {
            String text = edit.getText().toString().trim();
            if (text.isEmpty() || !AITranslator.isChineseOnly(text)) return;
            String cid = currentChatId;
            if (cid == null || cid.isEmpty() || "0".equals(cid) || "null".equals(cid)) {
                Toast.makeText(edit.getContext(), "\u26a0\ufe0f \u4f1a\u8bdd\u5c1a\u672a\u5c31\u7eea\uff0c\u8bf7\u9000\u51fa\u804a\u5929\u91cd\u65b0\u8fdb\u5165\u540e\u518d\u8bd5", Toast.LENGTH_SHORT).show(); return;
            }
            isTranslatingAPI = true; btn.setEnabled(false); btn.setText("..."); btn.setAlpha(1.0f);
            final String cs = cid; final int cts = currentChatType; final String pns = currentPartnerName;
            final String nats = latestNationality; final int nls = latestNativeLang;
            String quote = null; try { quote = getQuoteReplyText(edit.getRootView()); } catch (Exception ignored) {}
            final String qis = currentQuotedImagePath; final boolean qms = currentQuotedImageMissing;
            currentQuotedImagePath = null; currentQuotedImageMissing = false;

            boolean pbm = isPureBracketQuery(text);
            String ttt = text;
            if (!pbm && quote != null && !quote.trim().isEmpty()) {
                String orig = AITranslator.getForeignFuzzy(quote); if (orig != null) quote = orig;
                ttt = "\u3010\u6211\u8981\u56de\u590d\u7684\u5bf9\u65b9\u539f\u8bdd\u3011\uff1a" + quote.trim() + "\n\u3010\u6211\u7684\u56de\u590d\u3011\uff1a" + text;
            }
            if (pbm) ttt = "[PURE_BRACKET_MODE]\n" + ttt;
            if (qis != null) { File qf = new File(qis); if (qf.exists() && qf.length() > 0) ttt += "\n[QUOTED_LOCAL_IMAGE:" + qis + "]"; }
            else if (qms) ttt += "\n[QUOTED_IMAGE_BUT_PATH_MISSING]";

            final String ftt = ttt, rci = text;
            new Thread(() -> {
                try {
                    String tl = determineSmartTargetLang(nats, nls, cs);
                    if (cts == 1) AITranslator.registerFriend(cs, pns, tl, nats);
                    String lr = chatRequestMap.get(cs); int rc = chatRetryCountMap.getOrDefault(cs, 0);
                    boolean ir = ftt.equals(lr);
                    if (ir) { rc++; chatRetryCountMap.put(cs, rc); }
                    else { chatRequestMap.put(cs, ftt); chatRetryCountMap.put(cs, 0); }
                    String fpt = ftt; if (ir) fpt = ftt + "\n\n\u3010\u7cfb\u7edf\u5f3a\u5236\u6307\u4ee4\u3011\uff1a\u7528\u6237\u8981\u6c42\u91cd\u65b0\u751f\u6210\u3002\u8bf7\u7ed9\u51fa\u5b8c\u5168\u4e0d\u540c\u7684\u8868\u8fbe\u65b9\u5f0f\uff01";
                    Integer lsc = chatShortCountMap.get(cs);
                    if (lsc != null && !ftt.contains("[PURE_BRACKET_MODE]")) {
                        if (lsc > 0) fpt += "\n\u3010\u7cfb\u7edf\u5f3a\u5236\u683c\u5f0f\u63d0\u9192\u3011\uff1a\u4e0a\u4e00\u6b21\u53ea\u8f93\u51fa\u4e86" + lsc + "\u4e2a\u7248\u672c\uff0c\u672c\u6b21\u5fc5\u987b\u6070\u597d6\u884c\uff01";
                        else fpt += "\n\u3010\u7cfb\u7edf\u5f3a\u5236\u683c\u5f0f\u63d0\u9192\u3011\uff1a\u4e0a\u4e00\u6b21\u6ca1\u6709\u6709\u6548\u7ffb\u8bd1\u9009\u9879\uff0c\u672c\u6b21\u5fc5\u987b\u6070\u597d6\u884c\uff01";
                    }
                    // ★ 不做自动重试：AI给几个就显示几个；下次点【译】或弹窗【换一批】即为手动重试
                    String result = AITranslator.translateForPicker(fpt, tl, cs);
                    if (!ftt.contains("[PURE_BRACKET_MODE]")) {
                        int oc = AITranslator.parseTranslateOptions(result).size();
                        if (oc >= 4) chatShortCountMap.remove(cs); else chatShortCountMap.put(cs, oc);
                    }
                    isTranslatingAPI = false; String fr = result;
                    edit.post(() -> { btn.setEnabled(true); btn.setText("\u8bd1"); btn.setAlpha(0.92f); showPicker(edit, btn, fr, rci, pns); });
                } catch (Exception e) {
                    isTranslatingAPI = false; chatRequestMap.remove(cs); chatRetryCountMap.put(cs, 0);
                    edit.post(() -> { btn.setEnabled(true); btn.setText("\u8bd1"); btn.setAlpha(0.88f);
                        Toast.makeText(edit.getContext(), "\u26a0\ufe0f \u7ffb\u8bd1\u5931\u8d25: " + (e.getMessage() != null ? e.getMessage() : "\u672a\u77e5\u9519\u8bef"), Toast.LENGTH_LONG).show(); });
                }
            }).start();
        });
    }

    private static String determineSmartTargetLang(String nat, int nl, String cid) {
        String n = nat == null ? "" : nat.toLowerCase();
        if (!n.isEmpty()) { String ml = mapNationalityToLang(n); if (ml != null) return ml; }
        String lc = getDynamicLangCode(nl);
        if (lc != null && !lc.isEmpty() && !"en".equals(lc)) return lc;
        String fl = AITranslator.getFriendLang(cid);
        if (fl != null && !fl.isEmpty()) { if (fl.equalsIgnoreCase("zh") || fl.startsWith("zh")) return DEFAULT_REPLY_LANG; return fl; }
        return DEFAULT_REPLY_LANG;
    }

    private static String getDynamicLangCode(int nl) {
        if (langCodeMethod != null) try { String r = (String) langCodeMethod.invoke(null, nl); return r != null ? r.toLowerCase() : null; } catch (Exception ignored) {}
        return null;
    }

    private static String mapNationalityToLang(String nat) {
        if (nat == null || nat.isEmpty()) return null;
        switch (nat) {
            case "china": case "taiwan": case "hong kong": case "macau": case "singapore": return "zh";
            case "russia": case "belarus": case "kazakhstan": case "kyrgyzstan": return "ru";
            case "japan": return "ja";
            case "korea": case "south korea": return "ko";
            case "france": case "belgium": case "switzerland": case "canada": return "fr";
            case "germany": case "austria": return "de";
            case "spain": case "mexico": case "argentina": case "colombia": case "peru":
            case "chile": case "venezuela": case "ecuador": case "bolivia": case "paraguay":
            case "uruguay": case "costa rica": case "panama": case "nicaragua": case "honduras":
            case "el salvador": case "guatemala": case "cuba": case "dominican republic": case "puerto rico": return "es";
            case "italy": return "it";
            case "portugal": case "brazil": return "pt";
            case "arabia": case "egypt": case "saudi arabia": case "united arab emirates":
            case "morocco": case "algeria": case "tunisia": case "jordan": case "lebanon":
            case "iraq": case "kuwait": case "qatar": case "oman": case "bahrain": return "ar";
            case "turkey": return "tr"; case "netherlands": return "nl"; case "poland": return "pl";
            case "vietnam": return "vi"; case "thailand": return "th"; case "indonesia": return "id";
            case "india": return "hi"; case "ukraine": return "uk";
            default: return null;
        }
    }

    // =========================================================
    // Picker dialog
    // =========================================================

    private static void showPicker(EditText edit, Button btn, String result, String origChinese, String pn) {
        android.content.Context ctx = edit.getContext();
        String at = AITranslator.extractAnalysis(result);
        List<String[]> items = AITranslator.parseTranslateOptions(result);
        if (items.isEmpty()) { Toast.makeText(ctx, "\u26a0\ufe0f AI \u8fd4\u56de\u683c\u5f0f\u5f02\u5e38", Toast.LENGTH_LONG).show(); return; }

        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);

        if (at != null && !at.isEmpty()) {
            android.widget.ScrollView ts = new android.widget.ScrollView(ctx);
            ts.setLayoutParams(new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
            ts.setPadding(40, 20, 40, 10);
            TextView ta = new TextView(ctx); ta.setText(at); ta.setTextColor(Color.parseColor("#6C757D")); ta.setTextSize(13f); ta.setTextIsSelectable(true);
            ts.addView(ta); root.addView(ts);
        }

        android.widget.ScrollView bs = new android.widget.ScrollView(ctx);
        bs.setLayoutParams(new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2.0f));
        android.widget.LinearLayout cont = new android.widget.LinearLayout(ctx);
        cont.setOrientation(android.widget.LinearLayout.VERTICAL); cont.setPadding(40, 10, 40, 20);
        bs.addView(cont); root.addView(bs);

        String dn = (pn != null && !pn.isEmpty()) ? pn : currentPartnerName;
        String title = (dn != null && !dn.isEmpty()) ? ("\u9009\u7248\u672c - " + dn) : "\u9009\u7248\u672c";
        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(ctx)
                .setTitle(title).setView(root)
                .setNegativeButton("\u53d6\u6d88", (d, w) -> edit.post(() -> edit.setText(edit.getText().toString())))
                .setPositiveButton("\uD83D\uDD04 \u6362\u4e00\u6279", (d, w) -> edit.post(() -> btn.performClick()))
                .create();

        for (String[] item : items) {
            final String foreign = item[0]; String ch = item[1]; String lb = item[2];
            android.widget.LinearLayout card = new android.widget.LinearLayout(ctx);
            card.setOrientation(android.widget.LinearLayout.VERTICAL); card.setPadding(35, 35, 35, 35);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 10, 0, 15); card.setLayoutParams(lp);
            GradientDrawable cbg = new GradientDrawable(); cbg.setColor(Color.parseColor("#F8F9FA")); cbg.setCornerRadius(16f); cbg.setStroke(2, Color.parseColor("#E9ECEF"));
            card.setBackground(cbg);
            TextView tf = new TextView(ctx); tf.setText(foreign); tf.setTextColor(Color.parseColor("#212529")); tf.setTextSize(16f); tf.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(tf);
            if ((ch != null && !ch.isEmpty()) || (lb != null && !lb.isEmpty())) {
                TextView tc = new TextView(ctx);
                String st = ch != null ? ch : ""; if (lb != null && !lb.isEmpty()) st += " [" + lb + "]";
                tc.setText(st); tc.setTextColor(Color.parseColor("#6C757D")); tc.setTextSize(13f); tc.setPadding(0, 15, 0, 0);
                card.addView(tc);
            }
            card.setOnClickListener(v2 -> {
                AITranslator.rememberDraft(foreign, origChinese);
                edit.setText(foreign); edit.setSelection(foreign.length());
                try { ((android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("HT_AI_Copy", foreign)); } catch (Exception ignored) {}
                dialog.dismiss();
            });
            card.setOnLongClickListener(v2 -> {
                try { ((android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("HT_AI_Copy", foreign));
                Toast.makeText(ctx, "\u2705 \u5df2\u590d\u5236\u5230\u526a\u8d34\u677f", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
                return true;
            });
            cont.addView(card);
        }
        dialog.show();
    }
}

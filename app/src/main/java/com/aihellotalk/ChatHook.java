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

    private static Method langCodeMethod = null;
    private static Method langNameMethod = null;

    private static final int RECENT_IMAGE_LIMIT = 3;
    private static final ConcurrentHashMap<String, String> imageUrlToPathMap = new ConcurrentHashMap<>();
    private static final List<RenderedImageInfo> recentRenderedImages = Collections.synchronizedList(new ArrayList<>());

    private static volatile String latestRenderedImagePath = null;
    private static volatile long latestRenderedImageTime = 0;

    private static volatile String currentQuotedImagePath = null;
    private static volatile boolean currentQuotedImageMissing = false;

    private static class RenderedImageInfo {
        final String path;
        final String url;
        final String compressedUrl;
        final long ts;

        RenderedImageInfo(String path, String url, String compressedUrl, long ts) {
            this.path = path;
            this.url = url;
            this.compressedUrl = compressedUrl;
            this.ts = ts;
        }
    }

    public static void install(ClassLoader cl) {
        log("=== Hook v80.1 (止损稳态版-修复) ===");

        try {
            Class<?> avClass = XposedHelpers.findClass("av.a", cl);
            langCodeMethod = avClass.getMethod("a", int.class);
            langNameMethod = avClass.getMethod("b", int.class);
        } catch (Throwable ignored) {}

        try { hookTextViewRender(cl); } catch (Throwable ignored) {}
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

    // =========================================================
    // 基础工具
    // =========================================================

    private static void log(String msg) {
        XposedBridge.log("HT_AI " + msg);
    }

    private static boolean isPureBracketQuery(String text) {
        if (text == null) return false;
        String s = text.trim();
        return (s.startsWith("(") && s.endsWith(")")) ||
                (s.startsWith("（") && s.endsWith("）"));
    }

    private static String safeCallString(Object obj, String methodName) {
        if (obj == null) return null;
        try {
            Object r = XposedHelpers.callMethod(obj, methodName);
            return r == null ? null : String.valueOf(r);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safeNormalize(String s) {
        if (s == null) return null;
        try {
            String x = s.trim();
            if (x.isEmpty()) return null;
            int q = x.indexOf('?');
            if (q >= 0) x = x.substring(0, q);
            int h = x.indexOf('#');
            if (h >= 0) x = x.substring(0, h);
            try { x = URLDecoder.decode(x, "UTF-8"); } catch (Throwable ignored) {}
            return x.trim();
        } catch (Throwable e) {
            return s;
        }
    }

    private static String fileNameFromUrl(String url) {
        try {
            String s = safeNormalize(url);
            if (s == null || s.isEmpty()) return null;
            int idx = s.lastIndexOf('/');
            if (idx >= 0 && idx < s.length() - 1) return s.substring(idx + 1);
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File getHelloTalkImageCacheDir() {
        try {
            return new File("/storage/emulated/0/Android/data/com.hellotalk/cache/hellotalk/images_/");
        } catch (Throwable e) {
            return null;
        }
    }

    private static void putImageMapping(String key, String path) {
        if (key == null || key.trim().isEmpty() || path == null || path.trim().isEmpty()) return;
        imageUrlToPathMap.put(key, path);
    }

    private static void addRenderedImageRecord(String path, String url, String compressedUrl) {
        if (path == null || path.isEmpty()) return;
        long now = System.currentTimeMillis();
        latestRenderedImagePath = path;
        latestRenderedImageTime = now;

        synchronized (recentRenderedImages) {
            recentRenderedImages.add(0, new RenderedImageInfo(path, url, compressedUrl, now));

            Set<String> seen = ConcurrentHashMap.newKeySet();
            Iterator<RenderedImageInfo> it = recentRenderedImages.iterator();
            List<RenderedImageInfo> dedup = new ArrayList<>();
            while (it.hasNext()) {
                RenderedImageInfo info = it.next();
                if (info == null || info.path == null) continue;
                if (seen.add(info.path)) dedup.add(info);
            }
            recentRenderedImages.clear();
            recentRenderedImages.addAll(dedup);

            while (recentRenderedImages.size() > RECENT_IMAGE_LIMIT) {
                recentRenderedImages.remove(recentRenderedImages.size() - 1);
            }
        }
    }

    private static List<String> getRecentImagePaths(int limit) {
        List<String> result = new ArrayList<>();
        if (limit <= 0) return result;
        synchronized (recentRenderedImages) {
            for (RenderedImageInfo info : recentRenderedImages) {
                if (info != null && info.path != null) {
                    File f = new File(info.path);
                    if (f.exists() && f.length() > 0) {
                        result.add(info.path);
                        if (result.size() >= limit) break;
                    }
                }
            }
        }
        return result;
    }

    private static String bruteFindLocalImagePathFromBean(Object imageBean) {
        if (imageBean == null) return null;

        String url = safeCallString(imageBean, "getUrl");
        String compressedUrl = safeCallString(imageBean, "getCompressedUrl");

        String urlNorm = safeNormalize(url);
        String compressedNorm = safeNormalize(compressedUrl);

        String urlName = fileNameFromUrl(url);
        String compressedName = fileNameFromUrl(compressedUrl);

        File dir = getHelloTalkImageCacheDir();
        if (dir == null || !dir.exists() || !dir.isDirectory()) return null;

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return null;

        for (File f : files) {
            if (f == null || !f.exists() || f.length() <= 0) continue;
            String name = f.getName();

            if (urlName != null && !urlName.isEmpty() && name.contains(urlName)) {
                return f.getAbsolutePath();
            }
            if (compressedName != null && !compressedName.isEmpty() && name.contains(compressedName)) {
                return f.getAbsolutePath();
            }
        }

        synchronized (recentRenderedImages) {
            for (RenderedImageInfo info : recentRenderedImages) {
                if (info == null || info.path == null) continue;
                File f = new File(info.path);
                if (!f.exists() || f.length() <= 0) continue;

                String infoUrl = safeNormalize(info.url);
                String infoCompressed = safeNormalize(info.compressedUrl);

                if (urlNorm != null && infoUrl != null &&
                        (urlNorm.equals(infoUrl) || infoUrl.contains(urlNorm) || urlNorm.contains(infoUrl))) {
                    return info.path;
                }
                if (compressedNorm != null && infoCompressed != null &&
                        (compressedNorm.equals(infoCompressed) || infoCompressed.contains(compressedNorm) || compressedNorm.contains(infoCompressed))) {
                    return info.path;
                }

                String infoName = f.getName();
                if (urlName != null && infoName.contains(urlName)) return info.path;
                if (compressedName != null && infoName.contains(compressedName)) return info.path;
            }
        }

        return null;
    }

    // =========================================================
    // 防同步/防已读/防 typing
    // =========================================================

    private static void hookUltimateStealth(ClassLoader cl) {
        try {
            Class<?> titleControllerClass = XposedHelpers.findClassIfExists(
                    "com.hellotalk.talk.detail.controller.title.TalkSingleTitleController", cl);
            if (titleControllerClass != null) {
                XposedBridge.hookAllMethods(titleControllerClass, "s0", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(null);
                    }
                });
            }
        } catch (Throwable ignored) {}

        XC_MethodHook killReadHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.setResult(null);
            }
        };

        try {
            Class<?> z10aClass = XposedHelpers.findClassIfExists("z10.a", cl);
            if (z10aClass != null) {
                XposedBridge.hookAllMethods(z10aClass, "m", killReadHook);
                XposedBridge.hookAllMethods(z10aClass, "c0", killReadHook);
                XposedBridge.hookAllMethods(z10aClass, "f0", killReadHook);
            }

            Class<?> y10bClass = XposedHelpers.findClassIfExists("y10.b", cl);
            if (y10bClass != null) {
                XposedBridge.hookAllMethods(y10bClass, "m", killReadHook);
                XposedBridge.hookAllMethods(y10bClass, "c0", killReadHook);
                XposedBridge.hookAllMethods(y10bClass, "f0", killReadHook);
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> b20eClass = XposedHelpers.findClassIfExists("b20.e", cl);
            if (b20eClass != null) {
                XposedBridge.hookAllMethods(b20eClass, "z", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                            String packetClassName = param.args[0].getClass().getName();
                            if ("tm.a".equals(packetClassName) || "e20.c".equals(packetClassName)) {
                                param.setResult(null);
                            }
                        }
                    }
                });
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> e20cClass = XposedHelpers.findClassIfExists("e20.c", cl);
            if (e20cClass != null) {
                XposedBridge.hookAllMethods(e20cClass, "f", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(new byte[0]);
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    // =========================================================
    // 本地 UI 渲染图标，不改底层发送内容
    // =========================================================

    private static void hookTextViewRender(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.hellotalk.lib.ui.text.view.HTCompatTextView",
                    cl,
                    "setText",
                    CharSequence.class,
                    TextView.BufferType.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.thisObject instanceof android.widget.EditText) return;

                            CharSequence cs = (CharSequence) param.args[0];
                            if (cs == null) return;
                            String s = cs.toString();

                            if (s.endsWith(" 🌐") || s.endsWith(" 🔄")) return;

                            String myDraft = AITranslator.getDraftFuzzy(s);
                            if (myDraft != null && !myDraft.equals(s)) {
                                SpannableStringBuilder ssb = new SpannableStringBuilder(cs);
                                ssb.append(" 🌐");
                                param.args[0] = ssb;
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}
    }

    private static void hookClipboard(ClassLoader cl) {
        XC_MethodHook clipHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ClipData clip = (ClipData) param.args[0];
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null) {
                        String textStr = text.toString();

                        if (clip.getDescription() != null &&
                                "HT_AI_Copy".equals(clip.getDescription().getLabel())) {
                            return;
                        }

                        boolean hasIcon = textStr.endsWith(" 🌐") || textStr.endsWith(" 🔄");
                        boolean hasChinese = textStr.matches(".*[\\u4e00-\\u9fa5]+.*");
                        if (!hasIcon && !hasChinese) return;

                        try {
                            String orig = AITranslator.getForeignFuzzy(textStr);
                            if (orig != null && !orig.trim().isEmpty() && !orig.equals(textStr)) {
                                param.args[0] = ClipData.newPlainText("HT_AI", orig);
                            }
                        } catch (Throwable ignored) {}
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
    }

    private static void hookBubbleFlip(ClassLoader cl) throws Exception {
        XposedHelpers.findAndHookMethod(
                "com.hellotalk.lib.ui.text.view.HTCompatTextView",
                cl,
                "onTouchEvent",
                MotionEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        TextView tv = (TextView) param.thisObject;
                        MotionEvent ev = (MotionEvent) param.args[0];
                        if (ev == null) return;

                        CharSequence cs = tv.getText();
                        if (cs == null) return;
                        String s = cs.toString();

                        if (!s.endsWith(" 🔄") && !s.endsWith(" 🌐")) return;

                        Layout layout = tv.getLayout();
                        if (layout == null) return;

                        int line = layout.getLineForVertical((int) ev.getY());
                        int offset = layout.getOffsetForHorizontal(line, ev.getX());
                        int iconStart = s.length() - 2;
                        if (offset < iconStart) return;

                        if (ev.getAction() == MotionEvent.ACTION_UP) {
                            String clean = s.substring(0, iconStart).trim();
                            clean = clean.replaceAll("[\\s🌐🔄]+$", "");

                            if (s.endsWith(" 🔄")) {
                                String orig = AITranslator.getForeignByChinese(clean);
                                if (orig != null && !orig.equals(clean)) {
                                    orig = orig.replaceAll("[\\s🌐🔄]+$", "");
                                    tv.setText(orig + " 🌐");
                                }
                            } else if (s.endsWith(" 🌐")) {
                                String zh = AITranslator.getChineseByForeign(clean);
                                if (zh != null && !zh.equals(clean)) {
                                    zh = zh.replaceAll("[\\s🌐🔄]+$", "");
                                    tv.setText(zh + " 🔄");
                                }
                            }
                            param.setResult(true);
                        } else if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                            param.setResult(true);
                        }
                    }
                }
        );
    }

    // =========================================================
    // 会话上下文
    // =========================================================

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
            latestPartnerName = (nickName != null && !nickName.isEmpty()) ? nickName : (userName != null ? userName : "");
            if (!latestPartnerName.isEmpty()) {
                currentPartnerName = latestPartnerName;
            }
        } catch (Throwable ignored) {}
    }

    private static void hookLang(ClassLoader cl) throws Exception {
        Class<?> vm = XposedHelpers.findClass("com.hellotalk.talk.detail.data.source.ChatDetailViewModel", cl);
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

    // =========================================================
    // 回复图片 / 渲染层
    // =========================================================

    private static void hookImageRenderLayer(ClassLoader cl) {
        try {
            Class<?> imageMsgCardClass = XposedHelpers.findClassIfExists(
                    "com.hellotalk.talk.detail.widget.msgcard.ImageMsgCard", cl);

            if (imageMsgCardClass != null) {
                XposedBridge.hookAllMethods(imageMsgCardClass, "c", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args == null || param.args.length < 2) return;

                        Object imageBean = param.args[0];
                        Object filePathObj = param.args[1];
                        if (!(filePathObj instanceof String)) return;

                        String filePath = (String) filePathObj;
                        File imgFile = new File(filePath);
                        if (!imgFile.exists() || img

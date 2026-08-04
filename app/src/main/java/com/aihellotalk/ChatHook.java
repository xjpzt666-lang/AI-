package com.aihellotalk;

import android.app.AlertDialog;
import android.content.ClipData;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.Layout;
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

    private static final int RECENT_IMAGE_LIMIT = 3;
    private static final Map<String, String> imageUrlToPathMap = new ConcurrentHashMap<>();
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
        log("=== Hook v65.0 (终极无错收官版) ===");

        try {
            Class<?> avClass = XposedHelpers.findClass("av.a", cl);
            langCodeMethod = avClass.getMethod("a", int.class);
            langNameMethod = avClass.getMethod("b", int.class);
        } catch (Throwable ignored) {}

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

    private static void hookUltimateStealth(ClassLoader cl) {
        try {
            Class<?> titleControllerClass = XposedHelpers.findClassIfExists("com.hellotalk.talk.detail.controller.title.TalkSingleTitleController", cl);
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
                            if (packetClassName.equals("tm.a") || packetClassName.equals("e20.c")) {
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
                        if (!imgFile.exists() || imgFile.length() <= 0) return;

                        String url = null;
                        String compressedUrl = null;
                        try { url = (String) XposedHelpers.callMethod(imageBean, "getUrl"); } catch (Throwable ignored) {}
                        try { compressedUrl = (String) XposedHelpers.callMethod(imageBean, "getCompressedUrl"); } catch (Throwable ignored) {}

                        putImageMapping(url, filePath);
                        putImageMapping(compressedUrl, filePath);
                        putImageMapping(safeNormalize(url), filePath);
                        putImageMapping(safeNormalize(compressedUrl), filePath);

                        String urlName = fileNameFromUrl(url);
                        String compressedName = fileNameFromUrl(compressedUrl);
                        if (urlName != null) putImageMapping("fname:" + urlName, filePath);
                        if (compressedName != null) putImageMapping("fname:" + compressedName, filePath);

                        addRenderedImageRecord(filePath, url, compressedUrl);
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    private static void hookReplyMessageView(ClassLoader cl) {
        try {
            Class<?> replyViewClass = XposedHelpers.findClassIfExists(
                    "com.hellotalk.talk.detail.widget.ReplyMessageView", cl);

            if (replyViewClass != null) {
                XposedBridge.hookAllMethods(replyViewClass, "A", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object msg = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                        currentQuotedImagePath = null;
                        currentQuotedImageMissing = false;

                        if (msg == null) return;

                        String msgType = null;
                        try { msgType = (String) XposedHelpers.callMethod(msg, "getMsgType"); } catch (Throwable ignored) {}

                        if ("image".equals(msgType) || "photo".equals(msgType)) {
                            currentQuotedImageMissing = true;
                            log("【回复框状态】A入口检测到引用图片");
                        }
                    }
                });
                
                XposedBridge.hookAllMethods(replyViewClass, "B", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (param.args != null && param.args.length >= 2) {
                                Object msg = param.args[1];
                                if (msg != null) {
                                    String msgType = (String) XposedHelpers.callMethod(msg, "getMsgType");
                                    if ("image".equals(msgType)) {
                                        currentQuotedImageMissing = true;
                                        log("【ReplyMessageView.B命中图片分支】");
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });

                XposedBridge.hookAllMethods(replyViewClass, "setImageMessageImage", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args == null || param.args.length < 1) return;
                        Object imageBean = param.args[0];
                        if (imageBean == null) return;

                        String localPath = bruteFindLocalImagePathFromBean(imageBean);
                        if (localPath != null) {
                            File f = new File(localPath);
                            if (f.exists() && f.length() > 0) {
                                currentQuotedImagePath = localPath;
                                currentQuotedImageMissing = false;
                                log("【ReplyMessageView命中本地图】" + localPath);
                                return;
                            }
                        }

                        currentQuotedImagePath = null;
                        currentQuotedImageMissing = true;
                        log("【ReplyMessageView未命中】回复图片存在，但本地路径仍未反推出");
                    }
                });
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> replyHolderClass = XposedHelpers.findClassIfExists(
                    "com.hellotalk.talk.detail.widget.reply.ReplyHolderView", cl);

            if (replyHolderClass != null) {
                XposedBridge.hookAllMethods(replyHolderClass, "f", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (param.args != null && param.args.length >= 2) {
                                Object msg = param.args[1];
                                if (msg != null) {
                                    String msgType = (String) XposedHelpers.callMethod(msg, "getMsgType");
                                    if ("image".equals(msgType)) {
                                        currentQuotedImageMissing = true;
                                        log("【ReplyHolderView.f命中图片分支】");
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });

                XposedBridge.hookAllMethods(replyHolderClass, "setImageMessageImage", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args == null || param.args.length < 1) return;
                        Object imageBean = param.args[0];
                        if (imageBean == null) return;

                        String localPath = bruteFindLocalImagePathFromBean(imageBean);
                        if (localPath != null) {
                            File f = new File(localPath);
                            if (f.exists() && f.length() > 0) {
                                currentQuotedImagePath = localPath;
                                currentQuotedImageMissing = false;
                                log("【ReplyHolderView命中本地图】" + localPath);
                                return;
                            }
                        }
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

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
                    try { cidInt = (Integer) XposedHelpers.callMethod(msg, "getChatId"); } catch (Exception ignored) {}
                    final String thisChatId = String.valueOf(cidInt);
                    currentChatId = thisChatId;

                    String senderName = null;
                    try { senderName = (String) XposedHelpers.callMethod(msg, "getSenderName"); } catch (Exception ignored) {}
                    if (senderName != null && !senderName.isEmpty() && !isMine) {
                        currentPartnerName = senderName;
                    }

                    String text = null;
                    try { text = (String) XposedHelpers.callMethod(bean, "getText"); } catch (Exception ignored) {}

                    String msgType = null;
                    try { msgType = (String) XposedHelpers.callMethod(msg, "getMsgType"); } catch (Exception ignored) {}

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
                    try { mid = (String) XposedHelpers.callMethod(msg, "getMsgId"); } catch (Exception ignored) {}
                    if (mid == null || mid.isEmpty()) mid = "n_" + text.hashCode();

                    long sendTime = System.currentTimeMillis();
                    try { sendTime = (Long) XposedHelpers.callMethod(msg, "getSendTime"); } catch (Exception ignored) {}

                    String quotedText = null;
                    try {
                        Object replyInfo = XposedHelpers.callMethod(msg, "getReplyInfo");
                        if (replyInfo != null && !isMine) {
                            String rMsgType = (String) XposedHelpers.callMethod(replyInfo, "getMsgType");
                            if ("text".equals(rMsgType)) {
                                Class<?> jsonBeanClass = XposedHelpers.findClass("com.hellotalk.lib.im.entity.base.HTIMJsonBean", cl);
                                Object contentBean = XposedHelpers.callMethod(replyInfo, "getMessageContent", jsonBeanClass, true);
                                if (contentBean != null) quotedText = (String) XposedHelpers.callMethod(contentBean, "getText");
                            } else {
                                quotedText = "[" + rMsgType + "]";
                            }
                        }
                    } catch (Exception ignored) {}

                    boolean isNewMessage = recordedMsgIds.add(thisChatId + "_" + mid);
                    if (isNewMessage) {
                        if (isMine) AITranslator.appendHistory(thisChatId, mid, "assistant", text, sendTime, null);
                        else AITranslator.appendHistory(thisChatId, mid, "user", text, sendTime, quotedText);
                    }

                    if (text.startsWith("[")) return;
                    if (AITranslator.containsJapanese(text) || AITranslator.isChineseOnly(text)) return;

                    if (isMine) {
                        String myChineseDraft = AITranslator.getDraftFuzzy(text);
                        if (myChineseDraft != null) {
                            AITranslator.cacheResult(mid, text, myChineseDraft);
                            String cleanText = text.replaceAll("[\\s🌐🔄]+$", "");
                            try { XposedHelpers.callMethod(bean, "setText", cleanText + " 🌐"); } catch (Exception ignored) {}
                        } else {
                            String[] cached = AITranslator.getCached(mid);
                            if (cached != null) {
                                String cleanCached = cached[0].replaceAll("[\\s🌐🔄]+$", "");
                                try { XposedHelpers.callMethod(bean, "setText", cleanCached + " 🌐"); } catch (Exception ignored) {}
                            }
                        }
                        return;
                    }

                    String[] cached = AITranslator.getCached(mid);
                    if (cached != null) {
                        String cleanCached = cached[1].replaceAll("[\\s🌐🔄]+$", "");
                        try { XposedHelpers.callMethod(bean, "setText", cleanCached + " 🔄"); } catch (Exception ignored) {}
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
                                String cleanResult = t.replaceAll("[\\s🌐🔄]+$", "");
                                try { XposedHelpers.callMethod(finalBean, "setText", cleanResult + " 🔄"); } catch (Exception ignored) {}
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

    private static void hookClipboard(ClassLoader cl) {
        XC_MethodHook clipHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ClipData clip = (ClipData) param.args[0];
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null) {
                        String textStr = text.toString();
                        if (clip.getDescription() != null && "HT_AI_Copy".equals(clip.getDescription().getLabel())) return;
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
        try { XposedHelpers.findAndHookMethod("android.content.ClipboardManager", cl, "setPrimaryClip", ClipData.class, clipHook); } catch (Throwable ignored) {}
    }

    private static void hookBubbleFlip(ClassLoader cl) throws Exception {
        XposedHelpers.findAndHookMethod("com.hellotalk.lib.ui.text.view.HTCompatTextView", cl, "onTouchEvent", MotionEvent.class, new XC_MethodHook() {
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
        });
    }

    private static void hookStartChat(ClassLoader cl) throws Exception {
        XposedHelpers.findAndHookMethod("com.hellotalk.talk.detail.data.source.ChatDetailViewModel", cl, "startChat", int.class, int.class, new XC_MethodHook() {
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
                            if (chatUser != null) { updateFromChatUser(chatUser); return; }
                            Thread.sleep(500);
                        }
                    } catch (Exception ignored) {}
                }).start();
            }
        });
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
            if (!latestPartnerName.isEmpty()) currentPartnerName = latestPartnerName;
        } catch (Throwable ignored) {}
    }

    private static void hookLang(ClassLoader cl) throws Exception {
        Class<?> vm = XposedHelpers.findClass("com.hellotalk.talk.detail.data.source.ChatDetailViewModel", cl);
        Field uf = vm.getDeclaredField("chatUser");
        uf.setAccessible(true);
        Class<?> hm = cl.loadClass("com.hellotalk.lib.im.entity.HTIMMessage");
        XposedHelpers.findAndHookMethod(vm, "generateChatMessage", hm, boolean.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object u = uf.get(p.thisObject);
                    if (u != null) partnerLang = (Integer) XposedHelpers.callMethod(u, "getNativeLang");
                } catch (Throwable ignored) {}
            }
        });
    }

    private static String getQuoteReplyText(View rootView) {
        if (rootView == null) return null;
        if (rootView instanceof TextView) {
            TextView tv = (TextView) rootView;
            try {
                String idName = tv.getResources().getResourceEntryName(tv.getId());
                if (idName != null && idName.equalsIgnoreCase("tvReplyDesc")) {
                    if (tv.getVisibility() == View.VISIBLE) return tv.getText().toString();
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

    private static View findNativeSendButtonSafely(ViewGroup root) {
        if (root == null) return null;
        ArrayList<View> views = new ArrayList<>();
        views.add(root);
        for (int i = 0; i < views.size(); i++) {
            View current = views.get(i);
            try {
                if (current.getId() != View.NO_ID) {
                    String idName = current.getResources().getResourceEntryName(current.getId());
                    if (idName != null && idName.toLowerCase().contains("send")) return current;
                }
            } catch (Exception ignored) {}
            if (current instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) current;
                for (int j = 0; j < vg.getChildCount(); j++) views.add(vg.getChildAt(j));
            }
        }
        return null;
    }

    private static void hookBtnOld(ClassLoader cl) throws Exception {
        Class<?> boxClass = XposedHelpers.findClass("com.hellotalk.chat.ui.ChatInputBoxView", cl);
        XposedBridge.hookAllConstructors(boxClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                v.postDelayed(() -> tryAddBtn_Old(v), 2000);
            }
        });
    }

    private static void hookBtnNew(ClassLoader cl) throws Exception {
        Class<?> operateClass = XposedHelpers.findClass("com.hellotalk.talk.detail.widget.input.ChatInputUIOperate", cl);
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
        try {
            edit.setLongClickable(true);
            edit.setTextIsSelectable(true);
            edit.setFocusable(true);
            edit.setFocusableInTouchMode(true);
        } catch (Throwable ignored) {}

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

        final View[] cachedNativeSendBtn = new View[1];

        Runnable enforceVisibility = new Runnable() {
            @Override
            public void run() {
                if (cachedNativeSendBtn[0] == null) {
                    cachedNativeSendBtn[0] = findNativeSendButtonSafely(layout);
                }
                String currentText = edit.getText().toString();
                String textWithoutAt = currentText.replace("@", "");
                if (!currentText.trim().isEmpty() && AITranslator.isChineseOnly(textWithoutAt)) {
                    if (!isTranslatingAPI) {
                        btn.setVisibility(View.VISIBLE);
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.93f);
                    }
                    if (cachedNativeSendBtn[0] != null) cachedNativeSendBtn[0].setVisibility(View.GONE);
                } else {
                    if (!isTranslatingAPI) btn.setVisibility(View.GONE);
                    if (cachedNativeSendBtn[0] != null && !currentText.trim().isEmpty()) cachedNativeSendBtn[0].setVisibility(View.VISIBLE);
                }
            }
        };

        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) { edit.post(enforceVisibility); }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (s == null) return;
                String currentText = s.toString();
                if (isTranslatingAPI && currentText.contains("@")) {
                    AITranslator.cancelOngoingTranslation();
                    String cleanText = currentText.replace("@", "");
                    edit.removeTextChangedListener(this);
                    edit.setText(cleanText);
                    edit.setSelection(cleanText.length());
                    edit.addTextChangedListener(this);
                }
            }
        });

        edit.postDelayed(enforceVisibility, 100);
        edit.postDelayed(enforceVisibility, 500);

        btn.setOnClickListener(v -> {
            String text = edit.getText().toString().trim();
            if (text.isEmpty() || !AITranslator.isChineseOnly(text)) return;

            isTranslatingAPI = true;
            btn.setEnabled(false);
            btn.setText("...");
            btn.setAlpha(1.0f);

            String quoteText = null;
            try { quoteText = getQuoteReplyText(edit.getRootView()); } catch (Exception ignored) {}

            final String quotedImageSnapshot = currentQuotedImagePath;
            final boolean quotedMissingSnapshot = currentQuotedImageMissing;

            currentQuotedImagePath = null;
            currentQuotedImageMissing = false;

            boolean pureBracketMode = isPureBracketQuery(text);
            String textToTranslate = text;

            if (!pureBracketMode && quoteText != null && !quoteText.trim().isEmpty()) {
                String orig = AITranslator.getForeignFuzzy(quoteText);
                if (orig != null) quoteText = orig;
                textToTranslate = "【我要回复的对方原话】：" + quoteText.trim() + "\n【我的回复】：" + text;
            } else {
                textToTranslate = text;
            }
            
            if (pureBracketMode) {
                textToTranslate = "[PURE_BRACKET_MODE]\n" + textToTranslate;
            }

            // 0张背景图限制，极致提速
            List<String> recentImages = getRecentImagePaths(0); 

            if (quotedImageSnapshot != null) {
                File quoted = new File(quotedImageSnapshot);
                if (quoted.exists() && quoted.length() > 0) {
                    textToTranslate += "\n[QUOTED_LOCAL_IMAGE:" + quotedImageSnapshot + "]";
                    log("【焦点图】已附带回复目标图: " + quotedImageSnapshot);
                }
            } else if (quotedMissingSnapshot) {
                textToTranslate += "\n[QUOTED_IMAGE_BUT_PATH_MISSING]";
                log("【焦点图缺失】回复的是图片，但本地路径未拿到，已显式告知AI");
            }

            for (String p : recentImages) {
                if (p == null || p.isEmpty()) continue;
                if (quotedImageSnapshot != null && quotedImageSnapshot.equals(p)) continue;
                File f = new File(p);
                if (f.exists() && f.length() > 0) {
                    textToTranslate += "\n[LOCAL_IMAGE:" + p + "]";
                }
            }

            final String finalTextToTranslate = textToTranslate;
            final String rawChineseInput = text;

            new Thread(() -> {
                try {
                    String targetLang = determineSmartTargetLang();
                    if (currentChatType == 1) AITranslator.registerFriend(currentChatId, currentPartnerName, targetLang);

                    String lastReq = chatRequestMap.get(currentChatId);
                    int retryCount = chatRetryCountMap.getOrDefault(currentChatId, 0);
                    boolean isRetry = finalTextToTranslate.equals(lastReq);
                    if (isRetry) {
                        retryCount++;
                        chatRetryCountMap.put(currentChatId, retryCount);
                    } else {
                        chatRequestMap.put(currentChatId, finalTextToTranslate);
                        chatRetryCountMap.put(currentChatId, 0);
                    }

                    String finalPromptText = finalTextToTranslate;
                    if (isRetry) {
                        finalPromptText = finalTextToTranslate + "\n\n【系统强制指令】：用户要求重新生成。请给出完全不同的表达方式！";
                    }

                    String result = AITranslator.translateWithHistory(finalPromptText, targetLang, currentChatId);

                    isTranslatingAPI = false;
                    edit.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.92f);
                        showPicker(edit, btn, result, rawChineseInput);
                    });
                } catch (Exception e) {
                    isTranslatingAPI = false;
                    edit.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("译");
                        btn.setAlpha(0.88f);
                        Toast.makeText(edit.getContext(), "翻译失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });
    }

    private static String determineSmartTargetLang() {
        String nationality = latestNationality.toLowerCase();
        if (!nationality.isEmpty()) {
            String mappedLang = mapNationalityToLang(nationality);
            if (mappedLang != null) return mappedLang;
        }
        int nativeLang = latestNativeLang;
        String langCode = getDynamicLangCode(nativeLang);
        String langName = getDynamicLangName(nativeLang);
        if (langName != null && langName.contains("Chinese")) return DEFAULT_REPLY_LANG;
        if (langCode != null && !langCode.isEmpty() && !"en".equals(langCode)) return langCode;

        String friendLang = AITranslator.getFriendLang(currentChatId);
        if (friendLang != null && !friendLang.isEmpty()) {
            if (friendLang.equalsIgnoreCase("zh") || friendLang.startsWith("zh")) return DEFAULT_REPLY_LANG;
            return friendLang;
        }
        return DEFAULT_REPLY_LANG;
    }

    private static String mapNationalityToLang(String nationality) {
        if (nationality == null || nationality.isEmpty()) return null;
        switch (nationality) {
            case "china": case "taiwan": case "hong kong": case "macau": return "zh";
            case "russia": case "belarus": case "kazakhstan": case "kyrgyzstan": return "ru";
            case "japan": return "ja";
            case "korea": case "south korea": return "ko";
            case "france": return "fr";
            case "germany": return "de";
            case "spain": return "es";
            case "italy": return "it";
            case "portugal": case "brazil": return "pt";
            case "arabia": case "egypt": return "ar";
            default: return null;
        }
    }

    private static void showPicker(EditText edit, Button translateBtn, String result, String originalChineseInput) {
        if (result == null || result.trim().isEmpty()) return;
        String analysisText = "";
        String optionsText = "";
        String[] splitData = result.split("={3,}");
        if (splitData.length >= 2) {
            analysisText = splitData[0].trim();
            optionsText = splitData[splitData.length - 1].trim();
        } else {
            StringBuilder anBuilder = new StringBuilder();
            StringBuilder opBuilder = new StringBuilder();
            boolean inOptions = false;
            for (String line : result.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.contains("下半部分") || trimmed.matches("^[=+\\-]{3,}.*$")) {
                    inOptions = true;
                    continue;
                }
                if (inOptions) opBuilder.append(trimmed).append("\n");
                else anBuilder.append(trimmed).append("\n\n");
            }
            analysisText = anBuilder.toString().trim();
            optionsText = opBuilder.toString().trim();
        }

        analysisText = analysisText.replace("*", "");
        List<String[]> parsedItems = new ArrayList<>();
        for (String line : optionsText.split("\n")) {
            String cleanLine = line.trim().replace("*", "");
            if (cleanLine.isEmpty() || cleanLine.matches("^[=+\\-]{3,}.*$")) continue;
            if (cleanLine.contains("|")) {
                cleanLine = cleanLine.replaceFirst("^(版本\\d*[：:\\s]*|Option\\s*\\d*[：:\\s]*|[\\-\\d一二三四五]+[\\.\\)、：:\\s]*)", "").trim();
                String[] parts = cleanLine.split("\\|");
                String foreignText = parts[0].trim().replaceAll("^[\"“'‘]+|[\"”'’]+$", "").trim();
                String chineseMean = parts.length > 1 ? parts[1].trim() : "";
                String labelText = parts.length > 2 ? parts[2].trim() : "";
                if (!foreignText.isEmpty()) parsedItems.add(new String[]{foreignText, chineseMean, labelText});
            }
        }

        if (parsedItems.isEmpty()) return;

        android.content.Context ctx = edit.getContext();
        android.widget.LinearLayout rootLayout = new android.widget.LinearLayout(ctx);
        rootLayout.setOrientation(android.widget.LinearLayout.VERTICAL);

        if (!analysisText.isEmpty()) {
            android.widget.ScrollView topScroll = new android.widget.ScrollView(ctx);
            android.widget.LinearLayout.LayoutParams topParams = new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
            topParams.setMargins(0, 10, 0, 10);
            topScroll.setLayoutParams(topParams);
            topScroll.setPadding(40, 20, 40, 10);
            TextView tvAnalysis = new TextView(ctx);
            tvAnalysis.setText(analysisText);
            tvAnalysis.setTextColor(Color.parseColor("#6C757D"));
            tvAnalysis.setTextSize(13f);
            tvAnalysis.setTextIsSelectable(true);
            topScroll.addView(tvAnalysis);
            rootLayout.addView(topScroll);
        }

        android.widget.ScrollView bottomScroll = new android.widget.ScrollView(ctx);
        android.widget.LinearLayout.LayoutParams bottomParams = new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2.0f);
        bottomScroll.setLayoutParams(bottomParams);

        android.widget.LinearLayout container = new android.widget.LinearLayout(ctx);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(40, 10, 40, 20);
        bottomScroll.addView(container);
        rootLayout.addView(bottomScroll);

        String displayName = !latestPartnerName.isEmpty() ? latestPartnerName : currentPartnerName;
        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("选版本 - " + displayName)
                .setView(rootLayout)
                .setNegativeButton("取消", (d, w) -> edit.post(() -> edit.setText(edit.getText().toString())))
                .setPositiveButton("🔄 换一批", (d, w) -> edit.post(() -> translateBtn.performClick()))
                .create();

        for (String[] item : parsedItems) {
            final String foreign = item[0];
            String chinese = item[1];
            String label = item[2];

            android.widget.LinearLayout card = new android.widget.LinearLayout(ctx);
            card.setOrientation(android.widget.LinearLayout.VERTICAL);
            card.setPadding(35, 35, 35, 35);
            android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 10, 0, 15);
            card.setLayoutParams(params);

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(Color.parseColor("#F8F9FA"));
            cardBg.setCornerRadius(16f);
            cardBg.setStroke(2, Color.parseColor("#E9ECEF"));
            card.setBackground(cardBg);

            TextView tvForeign = new TextView(ctx);
            tvForeign.setText(foreign);
            tvForeign.setTextColor(Color.parseColor("#212529"));
            tvForeign.setTextSize(16f);
            tvForeign.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(tvForeign);

            if (!chinese.isEmpty() || !label.isEmpty()) {
                TextView tvChinese = new TextView(ctx);
                String subText = chinese;
                if (!label.isEmpty()) subText += " [" + label + "]";
                tvChinese.setText(subText);
                tvChinese.setTextColor(Color.parseColor("#6C757D"));
                tvChinese.setTextSize(13f);
                tvChinese.setPadding(0, 15, 0, 0);
                card.addView(tvChinese);
            }

            card.setOnClickListener(v -> {
                AITranslator.mySentDrafts.put(foreign.trim(), originalChineseInput.trim());
                edit.setText(foreign);
                edit.setSelection(foreign.length());
                
                try {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        android.content.ClipData clip = android.content.ClipData.newPlainText("HT_AI_Copy", foreign);
                        clipboard.setPrimaryClip(clip);
                    }
                } catch (Exception ignored) {}

                dialog.dismiss();
            });

            card.setOnLongClickListener(v -> {
                try {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        android.content.ClipData clip = android.content.ClipData.newPlainText("HT_AI_Copy", foreign);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(ctx, "✅ 已复制到剪贴板，可直接粘贴", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception ignored) {}
                return true; 
            });

            container.addView(card);
        }
        dialog.show();
    }

    private static String getDynamicLangCode(int langId) {
        if (langCodeMethod != null) {
            try { return ((String) langCodeMethod.invoke(null, langId)).toLowerCase(); } catch (Exception ignored) {}
        }
        return "en";
    }

    private static String getDynamicLangName(int langId) {
        if (langNameMethod != null) {
            try { return (String) langNameMethod.invoke(null, langId); } catch (Exception ignored) {}
        }
        return "Unknown";
    }

    private static void log(String msg) {
        XposedBridge.log("HT_AI " + msg);
    }
}

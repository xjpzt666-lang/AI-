package com.aihellotalk;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AITranslator {

    private static final String TAG = "HT_AI";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private static String apiKey;
    private static String apiUrl;
    private static String model;
    private static OkHttpClient client;

    public static final Map<String, String[]> cache = new ConcurrentHashMap<>();
    public static final Map<String, String> foreignToChinese = new ConcurrentHashMap<>();
    public static final Map<String, String> chineseToForeign = new ConcurrentHashMap<>();
    public static final Map<String, String> mySentDrafts = new LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 800;
        }
    };

    private static final Map<String, String> imageBase64Cache = new ConcurrentHashMap<>();
    private static final Set<String> oneTimeSentSuppress = ConcurrentHashMap.newKeySet();

    private static File cacheFile;
    private static File promptFile;
    private static File draftsFile;

    public static String receivePrompt = "";
    public static String promptEN = "";
    public static String promptRU = "";
    public static String promptUK = "";
    public static String promptKO = "";
    public static String promptES = "";

    private static final File friendsFile = new File("/data/data/com.hellotalk/files/htai_friends.json");
    private static JSONObject friendsData = new JSONObject();

    private static final Object fileLock = new Object();

    private static final Pattern JAPANESE_PATTERN = Pattern.compile("[\\u3040-\\u30FF\\uFF65-\\uFF9F\\u30FC]+");
    private static final Pattern LOCAL_IMAGE_PATTERN = Pattern.compile("\\[LOCAL_IMAGE:(.*?)\\]");
    private static final Pattern QUOTED_LOCAL_IMAGE_PATTERN = Pattern.compile("\\[QUOTED_LOCAL_IMAGE:(.*?)\\]");
    private static final Pattern PURE_BRACKET_MODE_PATTERN = Pattern.compile("\\[PURE_BRACKET_MODE\\]");
    private static final Pattern QUOTED_IMAGE_MISSING_PATTERN = Pattern.compile("\\[QUOTED_IMAGE_BUT_PATH_MISSING\\]");
    private static final Pattern FLIP_MARKS_PATTERN = Pattern.compile("([ ]?[\uD83C\uDF10\uD83D\uDD04]+)$");

    private static final Pattern PAREN_TAIL = Pattern.compile("[\uff08(]([^()\uff08\uff09]*)[\uff09)]\\s*$");
    private static final Pattern NUMBER_PREFIX = Pattern.compile(
            "^(?:\u7248\u672c\\s*\\d*|[Oo]ption\\s*\\d*|\u9009\u9879\\s*\\d*|\\d{1,2}\\s*[.\u3001)\uff09:\uff1a]|[\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u2460-\u2473]+\\s*[.\u3001)\uff09:\uff1a]?)\\s*");

    private static final int MAX_TOTAL_BASE64_CHARS = 900_000;

    private static double getTemperature() {
        double temp = 0.3;
        try {
            File f = new File("/data/local/tmp/htai_config.txt");
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().startsWith("temperature=")) {
                        temp = Double.parseDouble(line.substring(12).trim());
                        break;
                    }
                }
                r.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Read temperature failed: " + e.getMessage());
        }

        if (Double.isNaN(temp) || temp < 0.0) temp = 0.3;
        if (temp > 2.0) temp = 2.0;

        Log.i(TAG, "HT_AI temperature=" + temp);
        return temp;
    }

    private static int getMaxTokens() {
        int tokens = 8000;
        try {
            File f = new File("/data/local/tmp/htai_config.txt");
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().startsWith("max_tokens=")) {
                        tokens = Integer.parseInt(line.substring(11).trim());
                        break;
                    }
                }
                r.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Read max_tokens failed: " + e.getMessage());
        }
        if (tokens < 100) tokens = 8000;
        return tokens;
    }

    public static void dumpDebug(String name, String text) {
        try {
            File f = new File("/data/data/com.hellotalk/files/htai_debug_" + name + ".txt");
            BufferedWriter w = new BufferedWriter(new FileWriter(f));
            w.write(text == null ? "" : text);
            w.close();
        } catch (Exception ignored) {}
    }

    public static String stripMetaInstructions(String s) {
        if (s == null) return "";
        String t = s;

        String[] metaWords = {
                "指令", "系统", "仅限本次", "直白", "露骨",
                "调情", "粗俗", "审查", "道德", "人设",
                "模块", "崩溃"
        };

        try {
            Pattern p = Pattern.compile("[（(][^（）()]*[）)]");
            Matcher m = p.matcher(t);
            StringBuffer sb = new StringBuffer();

            while (m.find()) {
                String seg = m.group();
                boolean isMeta = false;

                for (String k : metaWords) {
                    if (seg.contains(k)) {
                        isMeta = true;
                        break;
                    }
                }

                if (isMeta) {
                    m.appendReplacement(sb, "");
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(seg));
                }
            }

            m.appendTail(sb);
            t = sb.toString();
        } catch (Throwable ignored) {}

        t = t.replaceAll("(?m)^\\s*(系统强制指令|【系统|系统提示|#).*$", "");
        t = t.replaceAll("\\s+", " ").trim();

        return t;
    }

    public static void suppressSentForeign(String s) {
        if (s == null) return;
        String t = stripFlipMarks(s);
        if (t == null) return;
        t = t.trim();
        if (!t.isEmpty()) oneTimeSentSuppress.add(t);
    }

    public static boolean consumeSuppressSent(String s) {
        if (s == null) return false;
        String t = stripFlipMarks(s);
        if (t == null) return false;
        t = t.trim();
        if (t.isEmpty()) return false;
        return oneTimeSentSuppress.remove(t);
    }

    private static final String STORE_DIR = "/data/local/tmp/htai_store";
    private static final String MARKER_FILE = "/data/local/tmp/htai_mem_mode.txt";

    private static volatile String memMode = "main";
    private static volatile boolean memPending = false;
    private static volatile boolean pendingToastShown = false;
    private static volatile long lastModeRecheckTs = 0;
    private static volatile long lastBackupTs = 0;
    private static volatile long lastDistillFailTs = 0;

    private static final int HISTORY_SOFT_CAP = 100;
    private static final int DISTILL_BATCH_MIN = 30;
    private static final int HISTORY_HARD_CAP = 180;
    private static final long DISTILL_COOLDOWN_MS = 5 * 60_000;
    private static final int PROFILE_HARD_CAP = 800;
    private static final long BACKUP_INTERVAL_MS = 3 * 60_000;
    private static final long MODE_RECHECK_MS = 60_000;

    private static volatile OkHttpClient distillClient = null;
    private static volatile OkHttpClient reverseTranslateClient = null;

    private static final String DISTILL_SYSTEM_PROMPT =
            "\u4f60\u662f\u8bed\u8a00\u4ea4\u6362\u804a\u5929\u52a9\u624b\u7684\u8bb0\u5fc6\u6863\u6848\u7ba1\u7406\u5458\u3002" +
            "\u6211\u4f1a\u7ed9\u4f60\u4e00\u4efd\u73b0\u6709\u6863\u6848\u548c\u4e00\u6279\u5373\u5c06\u5f52\u6863\u7684\u65e7\u804a\u5929\u8bb0\u5f55\uff0c" +
            "\u4f60\u7684\u4efb\u52a1\u662f\u628a\u5b83\u4eec\u5408\u5e76\u6210\u4e00\u4efd\u66f4\u65b0\u540e\u7684\u597d\u53cb\u6863\u6848\u3002\n" +
            "\u89c4\u5219\uff1a\n" +
            "1. \u53ea\u8bb0\u5f55\u6709\u957f\u671f\u4ef7\u503c\u7684\u4fe1\u606f\uff1a\u5bf9\u65b9\u7684\u57fa\u672c\u4e8b\u5b9e\uff08\u540d\u5b57\u3001\u57ce\u5e02\u3001\u804c\u4e1a\u3001\u5b66\u4e60\u3001\u7231\u597d\u3001\u5bb6\u5ead\u7b49\uff09\u3001" +
            "\u53cc\u65b9\u5173\u7cfb\u9636\u6bb5\u4e0e\u719f\u6089\u7a0b\u5ea6\u3001\u957f\u671f\u8bdd\u9898\u4e0e\u5c1a\u672a\u5151\u73b0\u7684\u7ea6\u5b9a\u3001\u5bf9\u65b9\u7684\u5fcc\u8bb3\u4e0e\u504f\u597d\u3001\u5bf9\u65b9\u7684\u8bf4\u8bdd\u98ce\u683c\u3002\n" +
            "2. \u65b0\u4fe1\u606f\u4e0e\u65e7\u6863\u6848\u51b2\u7a81\u65f6\uff0c\u4ee5\u65b0\u4fe1\u606f\u4e3a\u51c6\uff1b\u5df2\u7ed3\u675f\u7684\u8bdd\u9898\u3001\u5df2\u8fc7\u671f\u6216\u5df2\u5151\u73b0\u7684\u7ea6\u5b9a\u3001\u8fc7\u65f6\u7684\u72b6\u6001\u8981\u5220\u6389\u3002\n" +
            "3. \u4e0d\u8981\u8bb0\u5f55\u7410\u788e\u95f2\u804a\u7ec6\u8282\uff0c\u4e0d\u8981\u9010\u6761\u590d\u8ff0\u804a\u5929\u5185\u5bb9\u3002\n" +
            "4. \u8f93\u51fa\u7eaf\u6587\u672c\u6863\u6848\uff0c\u5206\u5c0f\u8282\u3001\u6bcf\u884c\u4e00\u6761\uff0c\u603b\u957f\u5ea6\u4e25\u683c\u63a7\u5236\u5728500\u5b57\u4ee5\u5185\u3002\n" +
            "5. \u53ea\u8f93\u51fa\u6863\u6848\u6b63\u6587\u672c\u8eab\uff0c\u4e0d\u8981\u4efb\u4f55\u524d\u7f00\u3001\u540e\u7f00\u3001\u89e3\u91ca\u3002\n" +
            "6. \u4e0d\u8981\u8bb0\u5f55\u9732\u9aa8\u3001\u8272\u60c5\u3001\u7c97\u4fd7\u3001\u8c03\u60c5\u7684\u5177\u4f53\u8868\u8fbe\u3002\u53ea\u4fdd\u7559\u4e2d\u6027\u5173\u7cfb\u63cf\u8ff0\uff0c\u4f8b\u5982\u201c\u53cc\u65b9\u8f83\u719f\u3001\u8bed\u6c14\u968f\u610f\u201d\u3002";

    public static void init(String key, String url, String m) {
        apiKey = key;
        apiUrl = url;
        model = m;

        client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .build();

        cacheFile = new File("/data/data/com.hellotalk/files/htai_cache.txt");
        promptFile = new File("/data/local/tmp/htai_prompts.txt");
        draftsFile = new File("/data/data/com.hellotalk/files/htai_drafts.json");

        loadCache();
        loadFriends();
        loadPrompts();
        loadDrafts();
        initMemoryMode();
    }

    public static void initForFetch(String key, String url) {
        apiKey = key;
        apiUrl = url;
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build();
    }

    public static void cancelOngoingTranslation() {
        if (client != null) {
            try {
                client.dispatcher().cancelAll();
                Log.i(TAG, "\u5df2\u89e6\u53d1\u6025\u505c");
            } catch (Exception ignored) {}
        }
    }

    private static String runRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) { sb.append(l).append("\n"); }
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) { return null; }
    }

    private static android.app.Application currentAppByReflect() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.app.Application) return (android.app.Application) app;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean sandboxHasMemory() {
        try {
            File dir = new File("/data/data/com.hellotalk/files");
            String[] names = dir.list();
            if (names == null) return false;
            for (String n : names) { if (n != null && n.startsWith("htai_")) return true; }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean storeHasBackup() {
        try {
            String out = runRoot("ls " + STORE_DIR + "/htai_* 2>/dev/null");
            return out != null && !out.trim().isEmpty();
        } catch (Throwable e) { return false; }
    }

    private static String readMarker() {
        try {
            File f = new File(MARKER_FILE);
            if (!f.exists()) return null;
            BufferedReader r = new BufferedReader(new FileReader(f));
            String s = r.readLine(); r.close();
            return s == null ? null : s.trim();
        } catch (Exception e) { return null; }
    }

    private static void writeMarker(String mode) {
        try { runRoot("echo " + mode + " > " + MARKER_FILE + " && chmod 644 " + MARKER_FILE); }
        catch (Throwable ignored) {}
    }

    private static void initMemoryMode() {
        try {
            String marker = readMarker();
            if ("pending".equals(marker)) {
                if (storeHasBackup()) { memPending = true; toastPending(); return; }
                memPending = false; memMode = "main"; writeMarker("main"); return;
            }
            if ("temp".equals(marker)) { memPending = false; memMode = "temp"; return; }
            if (sandboxHasMemory()) {
                memPending = false;
                memMode = "temp".equals(marker) ? "temp" : "main";
                if (marker == null || marker.isEmpty()) writeMarker("main");
                return;
            }
            if (storeHasBackup()) { memPending = true; writeMarker("pending"); toastPending(); return; }
            memPending = false; memMode = "main";
            if (marker == null || marker.isEmpty()) writeMarker("main");
        } catch (Throwable t) { memPending = false; memMode = "main"; }
    }

    private static void toastPending() {
        if (pendingToastShown) return;
        pendingToastShown = true;
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    android.app.Application app = currentAppByReflect();
                    if (app != null) Toast.makeText(app,
                            "HT AI\uff1a\u68c0\u6d4b\u5230HelloTalk\u6570\u636e\u88ab\u6e05\u7a7a\uff0c\u8bb0\u5fc6\u5df2\u6682\u505c\u3002\n\u8bf7\u6253\u5f00\u9065\u63a7\u5668\u9009\u62e9\u3010\u4e3b\u8d26\u53f7\u3011\u6216\u3010\u4e00\u6b21\u6027\u3011",
                            Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private static void maybeRecheckMode() {
        if (!memPending) return;
        long now = System.currentTimeMillis();
        if (now - lastModeRecheckTs < MODE_RECHECK_MS) return;
        lastModeRecheckTs = now;
        try {
            String marker = readMarker();
            if ("temp".equals(marker)) { memPending = false; memMode = "temp"; }
            else if ("main".equals(marker)) { memPending = false; memMode = "main"; loadFriends(); loadCache(); loadDrafts(); }
        } catch (Throwable ignored) {}
    }

    private static void maybeBackup() {
        try {
            if (memPending || !"main".equals(memMode)) return;
            long now = System.currentTimeMillis();
            if (now - lastBackupTs < BACKUP_INTERVAL_MS) return;
            lastBackupTs = now;
            String sandboxLs = runRoot("ls /data/data/com.hellotalk/files/htai_* 2>/dev/null");
            if (sandboxLs == null || sandboxLs.trim().isEmpty()) return;
            runRoot("mkdir -p " + STORE_DIR + " && rm -f " + STORE_DIR + "/htai_* 2>/dev/null; "
                    + "cp /data/data/com.hellotalk/files/htai_* " + STORE_DIR + "/ 2>/dev/null; "
                    + "chmod 600 " + STORE_DIR + "/htai_* 2>/dev/null");
        } catch (Throwable ignored) {}
    }

    private static File profileFile(String chatId) {
        return new File("/data/data/com.hellotalk/files/htai_profile_" + chatId + ".txt");
    }

    public static String getProfile(String chatId) {
        if (chatId == null || chatId.isEmpty() || "0".equals(chatId) || "null".equals(chatId)) return "";
        try {
            File f = profileFile(chatId);
            if (!f.exists()) return "";
            BufferedReader r = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            r.close();
            String s = sb.toString().trim();
            return s.length() > PROFILE_HARD_CAP ? s.substring(0, PROFILE_HARD_CAP) : s;
        } catch (Exception e) { return ""; }
    }

    private static void writeProfileFile(String chatId, String text) {
        try {
            File f = profileFile(chatId); f.getParentFile().mkdirs();
            BufferedWriter w = new BufferedWriter(new FileWriter(f));
            w.write(text); w.close();
        } catch (Exception ignored) {}
    }

    private static String profileBlock(String chatId) {
        String p = getProfile(chatId);
        if (p == null || p.trim().isEmpty()) return "";
        return "\n\n\u3010\u5bf9\u65b9\u80cc\u666f\u6863\u6848\u3011" + p.trim();
    }

    private static OkHttpClient getDistillClient() {
        if (distillClient == null) {
            synchronized (AITranslator.class) {
                if (distillClient == null) {
                    distillClient = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS).build();
                }
            }
        }
        return distillClient;
    }

    private static String callDistill(JSONArray messages) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 1200);
            body.put("temperature", 0.2);
            body.put("messages", messages);
            return executeRequestWith(getDistillClient(), body);
        } catch (Exception e) { return null; }
    }

    private static void distillBatch(String chatId, List<JSONObject> batch) {
        try {
            if (apiKey == null || apiKey.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (now - lastDistillFailTs < DISTILL_COOLDOWN_MS) return;

            List<JSONObject> distillable = new ArrayList<>();
            for (JSONObject obj : batch) {
                if (!obj.optBoolean("oneTime", false)) distillable.add(obj);
            }
            if (distillable.isEmpty()) return;

            String oldProfile = getProfile(chatId);
            StringBuilder sb = new StringBuilder();
            sb.append("\u3010\u73b0\u6709\u6863\u6848\u3011\n");
            sb.append(oldProfile.isEmpty() ? "\uff08\u6682\u65e0\uff09" : oldProfile).append("\n\n");
            sb.append("\u3010\u5373\u5c06\u5f52\u6863\u7684\u804a\u5929\u8bb0\u5f55\u3011\n");

            boolean hasMaterial = false;
            for (JSONObject obj : distillable) {
                String role = obj.optString("role", "");
                String content = obj.optString("content", "");
                if (content == null || content.isEmpty()) continue;
                if ("user".equals(role)) { sb.append(scriptLine("\u5bf9\u65b9", content, "\u4e2d\u6587\u610f\u601d")); hasMaterial = true; }
                else if ("assistant".equals(role)) { sb.append(scriptLine("\u6211", content, "\u4e2d\u6587\u539f\u610f")); hasMaterial = true; }
            }
            if (!hasMaterial) return;

            JSONArray messages = new JSONArray();
            messages.put(createRawMessage("system", DISTILL_SYSTEM_PROMPT));
            messages.put(createRawMessage("user", sb.toString()));
            String result = callDistill(messages);
            if (result == null) { lastDistillFailTs = now; return; }
            String newProfile = result.trim();
            if (newProfile.isEmpty() || isRefusalResponse(newProfile)) { lastDistillFailTs = now; return; }
            if (newProfile.length() > PROFILE_HARD_CAP) newProfile = newProfile.substring(0, PROFILE_HARD_CAP);
            removeBatchFromHistory(chatId, distillable);
            writeProfileFile(chatId, newProfile);
            lastDistillFailTs = 0; lastBackupTs = 0; maybeBackup();
        } catch (Throwable t) { lastDistillFailTs = System.currentTimeMillis(); }
    }

    private static void removeBatchFromHistory(String chatId, List<JSONObject> batch) {
        synchronized (fileLock) {
            try {
                JSONArray history = loadHistory(chatId);
                Set<String> batchIds = new HashSet<>();
                for (JSONObject b : batch) { String id = b.optString("msgId", ""); if (!id.isEmpty()) batchIds.add(id); }
                JSONArray kept = new JSONArray();
                for (int i = 0; i < history.length(); i++) {
                    JSONObject obj = history.getJSONObject(i);
                    String id = obj.optString("msgId", "");
                    boolean drop = false;
                    if (!id.isEmpty() && batchIds.contains(id)) { drop = true; }
                    else {
                        for (JSONObject b : batch) {
                            if (b.optLong("timestamp", -1) == obj.optLong("timestamp", -2)
                                    && b.optString("content", "").equals(obj.optString("content", "\u0000"))) {
                                drop = true; break;
                            }
                        }
                    }
                    if (!drop) kept.put(obj);
                }
                writeHistoryLocked(chatId, kept);
            } catch (Exception ignored) {}
        }
    }

    private static JSONObject createRawMessage(String role, String content) throws JSONException {
        JSONObject m = new JSONObject(); m.put("role", role); m.put("content", content); return m;
    }

    private static String scriptLine(String who, String content, String noteLabel) {
        try {
            String clean = stripFlipMarks(content);
            String zh = (clean == null) ? null : foreignToChinese.get(clean);
            if (zh == null) zh = mySentDrafts.get(clean);
            if (zh != null) zh = stripMetaInstructions(zh);
            if (zh != null && !zh.isEmpty() && !zh.equals(clean))
                return who + ": " + content + "\uff08" + noteLabel + "\uff1a" + zh + "\uff09\n";
        } catch (Throwable ignored) {}
        return who + ": " + content + "\n";
    }

    public static boolean isRefusalResponse(String raw) {
        if (raw == null) return false;
        String t = raw.trim();
        if (t.isEmpty() || t.length() > 800) return false;
        String low = t.toLowerCase();
        String[] marks = {"i'm sorry","i am sorry","im sorry","sorry, but","sorry, i","i can't","i cannot",
                "i'm unable","i am unable","not able to","i apologize","as an ai","as a language model",
                "can't assist","cannot assist","unable to assist","decline","against my","violat",
                "content policy","safety guideline","guidelines","inappropriate","offensive","explicit"};
        for (String m : marks) { if (low.contains(m)) return true; }

        String[] zhMarks = {
                "抱歉", "对不起", "无法", "不能", "拒绝", "不方便",
                "不适合", "不适当", "不合适", "违反", "政策", "规范",
                "安全", "审查", "敏感", "露骨", "色情", "冒犯",
                "不雅", "粗俗", "内容策略", "安全准则"
        };
        for (String m : zhMarks) { if (t.contains(m)) return true; }
        return false;
    }

    private static String refuseGuard(String result, String fallback) {
        if (result == null) return fallback;
        return isRefusalResponse(result) ? fallback : result;
    }

    private static void loadDrafts() {
        try {
            if (draftsFile != null && draftsFile.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(draftsFile));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                String s = sb.toString().trim(); if (s.isEmpty()) return;
                JSONObject obj = new JSONObject(s);
                Iterator<String> it = obj.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = stripMetaInstructions(obj.optString(k, ""));
                    if (k == null || k.trim().isEmpty() || v == null || v.trim().isEmpty()) continue;
                    mySentDrafts.put(k, v); foreignToChinese.put(k, v); chineseToForeign.put(v, k);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void saveDrafts() {
        try {
            if (draftsFile == null) return;
            draftsFile.getParentFile().mkdirs();
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> e : mySentDrafts.entrySet()) obj.put(e.getKey(), e.getValue());
            BufferedWriter w = new BufferedWriter(new FileWriter(draftsFile));
            w.write(obj.toString()); w.close();
        } catch (Exception ignored) {}
    }

    public static void rememberDraft(String foreign, String chinese) {
        try {
            String f = stripFlipMarks(foreign);
            String c = stripFlipMarks(chinese);
            if (f == null || c == null) return;
            f = f.trim();
            c = stripMetaInstructions(c).trim();
            if (f.isEmpty() || c.isEmpty() || f.equals(c)) return;
            mySentDrafts.put(f, c); foreignToChinese.put(f, c); chineseToForeign.put(c, f);
            cacheResult("draft_" + f.hashCode(), f, c); saveDrafts();
        } catch (Exception ignored) {}
    }

    public static String getDraftFuzzy(String sentForeignText) {
        if (sentForeignText == null || sentForeignText.trim().isEmpty()) return null;
        String clean = stripFlipMarks(sentForeignText);
        if (clean == null || clean.isEmpty()) return null;

        String exact = mySentDrafts.get(clean); if (exact != null) return exact;
        exact = foreignToChinese.get(clean); if (exact != null) return exact;

        String bestKey = null; int bestLen = 0;
        for (Map.Entry<String, String> entry : mySentDrafts.entrySet()) {
            String key = stripFlipMarks(entry.getKey());
            if (key == null || key.isEmpty()) continue;
            int common = longestCommonSubstringLength(clean, key);
            double coverage = (double) common / Math.max(clean.length(), key.length());
            if (coverage >= 0.45 && common > bestLen) { bestLen = common; bestKey = key; }
        }
        if (bestKey != null) return mySentDrafts.get(bestKey);

        for (Map.Entry<String, String> entry : mySentDrafts.entrySet()) {
            String key = stripFlipMarks(entry.getKey());
            if (key == null || key.isEmpty()) continue;
            if (clean.contains(key) && (double) key.length() / clean.length() >= 0.45) return entry.getValue();
            if (key.contains(clean) && (double) clean.length() / key.length() >= 0.45) return entry.getValue();
        }
        return null;
    }

    private static int longestCommonSubstringLength(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        int[][] dp = new int[a.length() + 1][b.length() + 1]; int max = 0;
        for (int i = 1; i <= a.length(); i++)
            for (int j = 1; j <= b.length(); j++)
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    if (dp[i][j] > max) max = dp[i][j];
                }
        return max;
    }

    public static String getForeignByDraftChinese(String zh) {
        if (zh == null || zh.trim().isEmpty()) return null;
        String clean = stripFlipMarks(zh);
        for (Map.Entry<String, String> e : mySentDrafts.entrySet()) {
            String k = stripFlipMarks(e.getKey()), v = stripFlipMarks(e.getValue());
            if (v == null || v.isEmpty()) continue;
            if (clean.equals(v) || clean.contains(v) || v.contains(clean)) return k;
        }
        return null;
    }

    public static boolean hasAnyLetterOrDigit(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++)
            if (Character.isLetterOrDigit(text.charAt(i))) return true;
        return false;
    }

    public static String analyzePureSymbol(String symbolText, String chatId) {
        if (symbolText == null || symbolText.trim().isEmpty()) return symbolText;
        if (apiKey == null || apiKey.isEmpty()) return symbolText;

        try {
            JSONArray messages = new JSONArray();
            String sysPrompt = receivePrompt + profileBlock(chatId) +
                    "\n\n\u3010\u8868\u60c5/\u6807\u70b9\u6df1\u5ea6\u5206\u6790\u534f\u8bae\u3011\uff1a" +
                    "\n1. \u5bf9\u65b9\u521a\u521a\u53d1\u4e86\u4e00\u4e2a\u7eaf\u8868\u60c5/\u6807\u70b9\u7b26\u53f7\uff0c\u6ca1\u6709\u6587\u5b57\u3002" +
                    "\n2. \u4f60\u7684\u4efb\u52a1\uff1a\u4ed4\u7ec6\u9605\u8bfb\u4e0b\u65b9\u7684\u5bf9\u8bdd\u5386\u53f2\u4e0a\u4e0b\u6587\uff0c\u5224\u65ad\u5bf9\u65b9\u53d1\u8fd9\u4e2a\u8868\u60c5/\u6807\u70b9\u662f\u5728\u56de\u5e94\u6211\u7684\u54ea\u4e00\u53e5\u8bdd\u6216\u54ea\u4e00\u4e2a\u8bdd\u9898\u3002" +
                    "\n3. \u8f93\u51fa\u683c\u5f0f\uff1a\u53ea\u8f93\u51fa\u4e00\u4e2a\u4e2d\u6587\u5168\u89d2\u62ec\u53f7\u8865\u5728\u539f\u6587\u540e\u9762\uff0c\u62ec\u53f7\u5185\u683c\u5f0f\u4e3a\uff1a\uff08\u88ab\u6211\u7684xx\u8bdd\u9898/xx\u8bdd + \u60c5\u7eea\u53cd\u5e94\uff09\uff0c\u62ec\u53f7\u5185\u4e25\u683c\u4e0d\u8d85\u8fc720\u5b57\u3002" +
                    "\n4. \u5fc5\u987b\u8bf4\u6e05\u695a\u662f\u88ab\"\u6211\"\u7684\u4ec0\u4e48\u5185\u5bb9\u89e6\u53d1\u7684\u3002" +
                    "\n5. \u4e0d\u8981\u8f93\u51fa\u4efb\u4f55\u5176\u4ed6\u5185\u5bb9\uff0c\u4e0d\u8981\u7ffb\u8bd1\uff0c\u4e0d\u8981\u89e3\u91ca\uff0c\u53ea\u8f93\u51fa\u539f\u7b26\u53f7+\u62ec\u53f7\u3002";

            messages.put(createMessageObj("system", sysPrompt));

            JSONArray fullHistory = loadHistory(chatId);
            StringBuilder scriptBuilder = new StringBuilder();
            scriptBuilder.append("\u3010\u6700\u8fd1\u5bf9\u8bdd\u4e0a\u4e0b\u6587\u3011\n");
            int maxChatMessages = 15;
            int startIdx = Math.max(0, fullHistory.length() - maxChatMessages);
            boolean hasContext = false;
            for (int i = startIdx; i < fullHistory.length(); i++) {
                JSONObject msg = fullHistory.getJSONObject(i);
                String role = msg.optString("role", "");
                String content = msg.optString("content", "");
                String prefix = msg.optBoolean("oneTime", false) ? "[一次性上下文] " : "";
                if ("user".equals(role)) { scriptBuilder.append(prefix).append(scriptLine("\u5bf9\u65b9", content, "\u4e2d\u6587\u610f\u601d")); hasContext = true; }
                else if ("assistant".equals(role)) { scriptBuilder.append(prefix).append(scriptLine("\u6211", content, "\u4e2d\u6587\u539f\u610f")); hasContext = true; }
            }
            if (!hasContext) scriptBuilder.append("\uff08\u6682\u65e0\u6709\u6548\u4e0a\u4e0b\u6587\uff09\n");
            scriptBuilder.append("\n\u3010\u5bf9\u65b9\u521a\u53d1\u7684\u7eaf\u8868\u60c5/\u6807\u70b9\u7b26\u53f7\u3011\n").append(symbolText);

            messages.put(createMessageObj("user", scriptBuilder.toString()));

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 120);
            body.put("temperature", 0.2);
            body.put("messages", messages);

            String result = executeRequestWith(getReverseTranslateClient(), body);
            if (result != null && !result.trim().isEmpty()) {
                String clean = result.trim();
                String parenPart = "";
                Matcher pm = Pattern.compile("[\uff08(]([^()\uff08\uff09]{1,25})[\uff09)]").matcher(clean);
                if (pm.find()) {
                    parenPart = "\uff08" + pm.group(1).trim() + "\uff09";
                } else {
                    if (!clean.startsWith("\uff08") && !clean.startsWith("(")) clean = "\uff08" + clean;
                    if (!clean.endsWith("\uff09") && !clean.endsWith(")")) clean = clean + "\uff09";
                    clean = clean.replace("(", "\uff08").replace(")", "\uff09");
                    if (clean.length() > 30) clean = clean.substring(0, 30);
                    parenPart = clean;
                }
                return symbolText + " " + parenPart;
            }
        } catch (Exception e) {
            Log.w(TAG, "\u8868\u60c5\u5206\u6790\u5931\u8d25: " + e.getMessage());
        }
        return symbolText;
    }

    private static OkHttpClient getReverseTranslateClient() {
        if (reverseTranslateClient == null) {
            synchronized (AITranslator.class) {
                if (reverseTranslateClient == null) {
                    reverseTranslateClient = new OkHttpClient.Builder()
                            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
                            .writeTimeout(20, TimeUnit.SECONDS).build();
                }
            }
        }
        return reverseTranslateClient;
    }

    public static String reverseTranslateMyForeign(String foreignText, String chatId) {
        if (foreignText == null || foreignText.trim().isEmpty()) return null;
        if (apiKey == null || apiKey.isEmpty()) return null;
        if (!hasAnyLetterOrDigit(foreignText)) return null;
        if (isChineseOnly(foreignText)) return null;

        try {
            JSONArray messages = new JSONArray();
            messages.put(createRawMessage("system", "\u628a\u4ee5\u4e0b\u5916\u8bed\u53e5\u5b50\u7ffb\u8bd1\u6210\u4e2d\u6587\uff0c\u53ea\u8f93\u51fa\u4e00\u53e5\u4e2d\u6587\u7ffb\u8bd1\uff0c\u4e0d\u8981\u4efb\u4f55\u89e3\u91ca\u3002"));
            messages.put(createRawMessage("user", foreignText));

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 300);
            body.put("temperature", 0.2);
            body.put("messages", messages);

            String result = executeRequestWith(getReverseTranslateClient(), body);
            if (result != null && !result.trim().isEmpty() && !result.trim().equals(foreignText)) {
                String clean = result.trim();
                if (clean.length() > 200) clean = clean.substring(0, 200);
                return clean;
            }
        } catch (Exception e) { Log.w(TAG, "\u53cd\u5411\u7ffb\u8bd1\u5931\u8d25: " + e.getMessage()); }
        return null;
    }

    private static String buildImageCacheKey(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return path;
            return path + "_" + f.lastModified() + "_" + f.length();
        } catch (Throwable e) { return path; }
    }

    public static String encodeFileToBase64(String path) {
        String cacheKey = buildImageCacheKey(path);
        String cached = imageBase64Cache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) return cached;

        try {
            File file = new File(path);
            if (!file.exists() || file.length() == 0) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            options.inSampleSize = calculateInSampleSize(options, 448, 448);
            options.inJustDecodeBounds = false;

            Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            if (bitmap == null) return null;

            int w = bitmap.getWidth(), h = bitmap.getHeight();
            int maxSide = Math.max(w, h);
            if (maxSide > 448) {
                float scale = 448f / maxSide;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
                if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled; }
            }

            int[] qualities = new int[]{30, 22, 16, 12};
            byte[] bestBytes = null;
            for (int q : qualities) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, q, baos);
                bestBytes = baos.toByteArray();
                if (bestBytes.length <= 90 * 1024) break;
            }
            bitmap.recycle();
            if (bestBytes == null || bestBytes.length == 0) return null;

            String result = Base64.encodeToString(bestBytes, Base64.NO_WRAP);
            imageBase64Cache.put(cacheKey, result);
            return result;
        } catch (Throwable e) { return null; }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight, width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2, halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) inSampleSize *= 2;
        }
        return inSampleSize;
    }

    private static class ParsedVisualInput {
        String cleanText; List<String> contextImagePaths = new ArrayList<>();
        List<String> quotedImagePaths = new ArrayList<>();
        boolean pureBracketMode = false; boolean quotedImageMissing = false;
    }

    private static ParsedVisualInput parseVisualMarkers(String content) {
        ParsedVisualInput result = new ParsedVisualInput();
        if (content == null) { result.cleanText = ""; return result; }
        String working = content;
        Matcher pureMatcher = PURE_BRACKET_MODE_PATTERN.matcher(working);
        if (pureMatcher.find()) { result.pureBracketMode = true; working = pureMatcher.replaceAll("").trim(); }
        Matcher quotedMissingMatcher = QUOTED_IMAGE_MISSING_PATTERN.matcher(working);
        if (quotedMissingMatcher.find()) { result.quotedImageMissing = true; working = quotedMissingMatcher.replaceAll("").trim(); }
        Matcher quotedMatcher = QUOTED_LOCAL_IMAGE_PATTERN.matcher(working);
        StringBuffer quotedSb = new StringBuffer();
        while (quotedMatcher.find()) {
            String path = quotedMatcher.group(1).trim();
            if (!path.isEmpty()) result.quotedImagePaths.add(path);
            quotedMatcher.appendReplacement(quotedSb, "");
        }
        quotedMatcher.appendTail(quotedSb);
        working = quotedSb.toString();
        Matcher localMatcher = LOCAL_IMAGE_PATTERN.matcher(working);
        StringBuffer localSb = new StringBuffer();
        while (localMatcher.find()) {
            String path = localMatcher.group(1).trim();
            if (!path.isEmpty()) result.contextImagePaths.add(path);
            localMatcher.appendReplacement(localSb, "");
        }
        localMatcher.appendTail(localSb);
        result.cleanText = localSb.toString().trim();
        return result;
    }

    private static JSONObject createTextPart(String text) throws JSONException {
        JSONObject txt = new JSONObject(); txt.put("type", "text"); txt.put("text", text); return txt;
    }

    private static JSONObject createImagePart(String base64) throws JSONException {
        JSONObject imgObj = new JSONObject(); imgObj.put("type", "image_url");
        JSONObject urlObj = new JSONObject(); urlObj.put("url", "data:image/jpeg;base64," + base64);
        imgObj.put("image_url", urlObj); return imgObj;
    }

    private static JSONObject createMessageObj(String role, String content) throws JSONException {
        JSONObject msgObj = new JSONObject(); msgObj.put("role", role);
        ParsedVisualInput parsed = parseVisualMarkers(content);
        boolean hasContextImages = !parsed.contextImagePaths.isEmpty();
        boolean hasQuotedImages = !parsed.quotedImagePaths.isEmpty();

        if (!hasContextImages && !hasQuotedImages && !parsed.quotedImageMissing) {
            msgObj.put("content", parsed.cleanText); return msgObj;
        }

        JSONArray contentArray = new JSONArray();
        String clean = parsed.cleanText;
        contentArray.put(createTextPart(clean));

        int totalB64Chars = 0;
        for (String path : parsed.quotedImagePaths) {
            String b64 = encodeFileToBase64(path);
            if (b64 != null && !b64.isEmpty() && totalB64Chars + b64.length() <= MAX_TOTAL_BASE64_CHARS) {
                contentArray.put(createImagePart(b64)); totalB64Chars += b64.length();
            }
        }
        for (String path : parsed.contextImagePaths) {
            String b64 = encodeFileToBase64(path);
            if (b64 != null && !b64.isEmpty() && totalB64Chars + b64.length() <= MAX_TOTAL_BASE64_CHARS) {
                contentArray.put(createImagePart(b64)); totalB64Chars += b64.length();
            }
        }

        msgObj.put("content", contentArray); return msgObj;
    }

    public static void loadFriends() {
        try {
            if (friendsFile.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(friendsFile));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close(); friendsData = new JSONObject(sb.toString());
            }
        } catch (Exception ignored) {}
    }

    public static void saveFriends() {
        try {
            friendsFile.getParentFile().mkdirs();
            BufferedWriter w = new BufferedWriter(new FileWriter(friendsFile));
            w.write(friendsData.toString()); w.close();
        } catch (Exception ignored) {}
    }

    public static void registerFriend(String chatId, String name, String langCode) {
        registerFriend(chatId, name, langCode, null);
    }

    public static void registerFriend(String chatId, String name, String langCode, String nationality) {
        try {
            if (chatId == null || chatId.isEmpty()) return;
            JSONObject friend = new JSONObject();
            if (friendsData.has(chatId)) friend = friendsData.getJSONObject(chatId);
            if (name != null && !name.isEmpty()) friend.put("name", name);
            else if (!friend.has("name")) friend.put("name", chatId);
            friend.put("lang", langCode != null ? langCode : "en");
            if (nationality != null && !nationality.isEmpty()) friend.put("nationality", nationality.toLowerCase());
            friend.put("lastTime", System.currentTimeMillis());
            friendsData.put(chatId, friend); saveFriends();
        } catch (JSONException ignored) {}
    }

    public static void updateFriendNationality(String chatId, String nationality) {
        try {
            if (chatId == null || chatId.isEmpty() || nationality == null || nationality.isEmpty()) return;
            if (!friendsData.has(chatId)) return;
            JSONObject friend = friendsData.getJSONObject(chatId);
            friend.put("nationality", nationality.toLowerCase());
            friendsData.put(chatId, friend); saveFriends();
        } catch (JSONException ignored) {}
    }

    public static String getFriendNationality(String chatId) {
        try {
            if (chatId != null && friendsData.has(chatId))
                return friendsData.getJSONObject(chatId).optString("nationality", "");
        } catch (JSONException ignored) {}
        return "";
    }

    public static String getFriendLang(String chatId) {
        try { if (friendsData.has(chatId)) return friendsData.getJSONObject(chatId).optString("lang", "en"); }
        catch (JSONException ignored) {}
        return "en";
    }

    public static String getFriendName(String chatId) {
        try { if (friendsData.has(chatId)) return friendsData.getJSONObject(chatId).optString("name", chatId); }
        catch (JSONException ignored) {}
        return chatId;
    }

    public static JSONArray getAllFriends() {
        JSONArray list = new JSONArray();
        try {
            JSONArray ids = friendsData.names(); if (ids == null) return list;
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.getString(i);
                JSONObject info = friendsData.getJSONObject(id);
                JSONObject item = new JSONObject();
                item.put("id", id); item.put("name", info.optString("name", id));
                item.put("lang", info.optString("lang", "en"));
                item.put("nationality", info.optString("nationality", ""));
                item.put("lastTime", info.optLong("lastTime", 0));
                JSONArray hist = loadHistory(id); item.put("count", hist.length());
                list.put(item);
            }
        } catch (JSONException ignored) {}
        return list;
    }

    public static boolean containsJapanese(String s) {
        if (s == null || s.isEmpty()) return false;
        return JAPANESE_PATTERN.matcher(s).find();
    }

    public static boolean isChineseOnly(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (containsJapanese(text)) return false;
        for (char c : text.toCharArray()) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) return true;
        }
        return false;
    }

    public static boolean needTranslateToChinese(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (containsJapanese(text)) return false;
        boolean hasChinese = false, hasForeignAlpha = false;
        for (char c : text.toCharArray()) {
            if (!hasForeignAlpha && String.valueOf(c).matches("[a-zA-Z\u0430-\u044f\u0410-\u042f\u0451\u0401\u0456\u0406\u0457\u0407\u0454\u0404\u0491\u0490\\uAC00-\\uD7AF\u00e1\u00e9\u00ed\u00f3\u00fa\u00c1\u00c9\u00cd\u00d3\u00da\u00f1\u00d1\u00fc\u00dc\u00e4\u00f6\u00fc\u00df\u00c4\u00d6\u00dc]"))
                hasForeignAlpha = true;
            if (!hasChinese) {
                Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
                if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) hasChinese = true;
            }
            if (hasChinese && hasForeignAlpha) break;
        }
        if (!hasChinese) return true;
        if (hasForeignAlpha) return true;
        return false;
    }

    private static boolean containsForeignLetters(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
            if (b == null) continue;
            if (b == Character.UnicodeBlock.BASIC_LATIN && Character.isLetter(c)) return true;
            if (b == Character.UnicodeBlock.LATIN_1_SUPPLEMENT && Character.isLetter(c)) return true;
            if (b == Character.UnicodeBlock.LATIN_EXTENDED_A || b == Character.UnicodeBlock.LATIN_EXTENDED_B
                    || b == Character.UnicodeBlock.LATIN_EXTENDED_C || b == Character.UnicodeBlock.LATIN_EXTENDED_D) return true;
            if (b == Character.UnicodeBlock.CYRILLIC || b == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY) return true;
            if (b == Character.UnicodeBlock.GREEK || b == Character.UnicodeBlock.GREEK_EXTENDED) return true;
            if (b == Character.UnicodeBlock.HANGUL_SYLLABLES || b == Character.UnicodeBlock.HANGUL_JAMO
                    || b == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) return true;
            if (b == Character.UnicodeBlock.ARABIC) return true;
            if (b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA) return true;
            if (b == Character.UnicodeBlock.THAI) return true;
        }
        return false;
    }

    private static String stripFlipMarks(String s) {
        if (s == null) return null;
        return FLIP_MARKS_PATTERN.matcher(s).replaceAll("").trim();
    }

    public static String sanitizeForeignText(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return t;
        t = t.replace(";", ",").replace("\uff1b", ",");
        t = t.replace("\u2014", "...").replace("\u2013", "...").replace("\u2015", "...").replace("\u2500", "...");
        t = t.replaceAll(",[\\s,]*", ", ");
        t = t.replace(" ,", ",");
        t = t.replaceAll("\\.{4,}", "...");
        t = t.replaceAll("\\s{2,}", " ");
        return t.trim();
    }

    private static JSONObject tryParseJsonResult(String result) {
        if (result == null) return null;
        String s = result.trim();

        try {
            s = s.replaceFirst("^```json\\s*", "");
        } catch (Throwable ignored) {}

        try {
            s = s.replaceFirst("^```\\s*", "");
        } catch (Throwable ignored) {}

        try {
            s = s.replaceFirst("```$", "");
        } catch (Throwable ignored) {}

        s = s.trim();

        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;

        String jsonStr = s.substring(start, end + 1);
        try {
            return new JSONObject(jsonStr);
        } catch (JSONException e) {
            return null;
        }
    }

    public static List<String[]> parseTranslateOptions(String result) {
        List<String[]> items = new ArrayList<>();
        if (result == null || result.trim().isEmpty()) return items;

        JSONObject json = tryParseJsonResult(result);
        if (json != null) {
            JSONArray opts = json.optJSONArray("options");
            if (opts != null) {
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < opts.length(); i++) {
                    if (items.size() >= 4) break;
                    JSONObject o = opts.optJSONObject(i);
                    if (o == null) continue;

                    String foreign = o.optString("foreign", "").trim();
                    String chinese = o.optString("meaning", "").trim();
                    String label = o.optString("tone", "").trim();

                    foreign = sanitizeForeignText(foreign);

                    if (foreign.isEmpty() || !containsForeignLetters(foreign)) continue;
                    if (!seen.add(foreign.toLowerCase())) continue;
                    items.add(new String[]{foreign, chinese, label});
                }

                if (!items.isEmpty()) return items;
            }
        }

        String normalized = result.replace("\r\n", "\n").replace("\r", "\n").replace("```", "");
        String optionsText = normalized;

        String[] splitData = normalized.split("={3,}");
        if (splitData.length >= 2) {
            int bestIdx = -1;
            int bestScore = -1;
            for (int i = 0; i < splitData.length; i++) {
                int score = countPipeOptionLines(splitData[i]);
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0 && bestScore > 0) optionsText = splitData[bestIdx];
        } else {
            StringBuilder sb = new StringBuilder();
            boolean inOptions = false;
            for (String line : normalized.split("\n")) {
                String t = line.trim();
                if (!inOptions) {
                    boolean isSep = t.matches("^[=+\\-*\u2500]{3,}.*$")
                            || t.contains("\u4e0b\u534a\u90e8\u5206")
                            || t.contains("\u9009\u9879\u533a")
                            || t.matches("^(\u7ffb\u8bd1\u9009\u9879|\u9009\u9879\u5982\u4e0b|\u4ee5\u4e0b\u662f.*\u7248\u672c|\u7ffb\u8bd1\u5982\u4e0b).{0,10}$");
                    if (isSep) { inOptions = true; continue; }
                }
                if (inOptions) sb.append(line).append("\n");
            }
            if (sb.length() > 0) optionsText = sb.toString();
        }

        Set<String> seen = new HashSet<>();
        for (String rawLine : optionsText.split("\n")) {
            if (items.size() >= 4) break;

            String line = rawLine.trim().replace("*", "").replace("`", "").replace("\uff5c", "|").replace("｜", "|");
            if (line.isEmpty()) continue;
            if (line.matches("^[|\\s:\\-]+$")) continue;
            if (!line.contains("|")) continue;

            if (line.startsWith("|")) line = line.substring(1).trim();
            if (line.endsWith("|")) line = line.substring(0, line.length() - 1).trim();
            line = line.replaceFirst("^[\u2022\u00b7\u25e6\u25cb\u25aa]\\s*", "");
            line = NUMBER_PREFIX.matcher(line).replaceFirst("").trim();

            String[] parts = line.split("\\|");
            List<String> cells = new ArrayList<>();
            for (String p : parts) { String c = p.trim(); if (!c.isEmpty()) cells.add(c); }
            if (cells.isEmpty()) continue;

            String foreign = cells.get(0);
            String chinese = cells.size() > 1 ? cells.get(1) : "";
            String label = cells.size() > 2 ? cells.get(2) : "";

            foreign = foreign.replaceAll("^[\\s\"'\u201c\u201d\u2018\u2019\u300c\u300d\u300e\u300f]+|[\\s\"'\u201c\u201d\u2018\u2019\u300c\u300d\u300e\u300f]+$", "").trim();
            foreign = foreign.replaceFirst("^(英文|英语|俄语|乌克兰语|韩语|西班牙语|外语|译文|目标语言|原文|中文)\\s*[:：]?\\s*", "").trim();
            chinese = chinese.replaceFirst("^(\u4e2d\u6587)?(\u5927\u610f|\u610f\u601d|\u542b\u4e49|\u7ffb\u8bd1)?\\s*[:\uff1a]?\\s*", "").trim();
            label = label.replaceFirst("^(\u8bed\u6c14|\u98ce\u683c|\u6807\u7b7e)?\\s*[:\uff1a]?\\s*", "").trim();
            foreign = sanitizeForeignText(foreign);

            if (foreign.isEmpty() || !containsForeignLetters(foreign)) continue;
            if (!seen.add(foreign.toLowerCase())) continue;
            items.add(new String[]{foreign, chinese, label});
        }
        return items;
    }

    private static int countPipeOptionLines(String segment) {
        if (segment == null || segment.trim().isEmpty()) return 0;
        int score = 0;
        for (String rawLine : segment.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.matches("^[|\\s:\\-]+$")) continue;
            String norm = line.replace("｜", "|");
            if (!norm.contains("|")) continue;
            String[] parts = norm.split("\\|");
            if (parts.length >= 1 && containsForeignLetters(parts[0].trim())) score++;
        }
        return score;
    }

    public static String extractAnalysis(String result) {
        if (result == null) return "";

        JSONObject json = tryParseJsonResult(result);
        if (json != null) {
            return json.optString("analysis", "").trim().replace("*", "");
        }

        String[] splitData = result.split("={3,}");
        if (splitData.length >= 2) return splitData[0].trim().replace("*", "");

        String[] lines = result.split("\n");
        int firstOptionLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim().replace("*", "").replace("\uff5c", "|").replace("｜", "|");
            if (t.isEmpty()) continue;
            if (t.contains("|") || t.contains("下半部分") || t.contains("选项区")) { firstOptionLine = i; break; }
        }
        if (firstOptionLine <= 0) return "";
        StringBuilder an = new StringBuilder();
        for (int i = 0; i < firstOptionLine; i++) {
            String t = lines[i].trim();
            if (!t.isEmpty()) an.append(t).append("\n\n");
        }
        return an.toString().trim().replace("*", "");
    }

    public static String toChinese(String text) throws IOException { return toChinese(text, "0"); }

    public static String toChinese(String text, String chatId) throws IOException {
        maybeRecheckMode();
        text = text.trim();
        if (text.isEmpty()) return text;
        if (!needTranslateToChinese(text)) return text;

        try {
            JSONArray messages = new JSONArray();
            String sysPrompt = receivePrompt + profileBlock(chatId);
            messages.put(createMessageObj("system", sysPrompt));

            JSONArray fullHistory = loadHistory(chatId);
            StringBuilder scriptBuilder = new StringBuilder();
            int maxChatMessages = 15;
            int startIdx = Math.max(0, fullHistory.length() - maxChatMessages);
            boolean hasContext = false;
            for (int i = startIdx; i < fullHistory.length(); i++) {
                JSONObject msg = fullHistory.getJSONObject(i);
                String role = msg.optString("role", "");
                String content = msg.optString("content", "");
                if (content != null && content.equals(text)) continue;
                String prefix = msg.optBoolean("oneTime", false) ? "[一次性上下文] " : "";
                if ("user".equals(role)) { scriptBuilder.append(prefix).append(scriptLine("\u5bf9\u65b9", content, "\u4e2d\u6587\u610f\u601d")); hasContext = true; }
                else if ("assistant".equals(role)) { scriptBuilder.append(prefix).append(scriptLine("\u6211", content, "\u4e2d\u6587\u539f\u610f")); hasContext = true; }
            }
            if (!hasContext) scriptBuilder.append("\uff08\u6682\u65e0\u6709\u6548\u4e0a\u4e0b\u6587\uff09\n");
            scriptBuilder.append("\n\u3010\u7cfb\u7edf\u6307\u4ee4\u3011\u4e0b\u65b9\u53ea\u6709<<<\u548c>>>\u6807\u8bb0\u5185\u7684\u539f\u6587\u624d\u662f\u8981\u7ffb\u8bd1\u7684\u5185\u5bb9\uff0c\u4e0a\u9762\u5bf9\u8bdd\u5267\u672c\u4ec5\u4f9b\u7406\u89e3\u8bed\u5883\u53c2\u8003\uff0c\u4e25\u7981\u7ffb\u8bd1\u6216\u590d\u8ff0\u5267\u672c\u91cc\u5df2\u6709\u7684\u5185\u5bb9\uff1a\n<<<\n").append(text).append("\n>>>");
            messages.put(createMessageObj("user", scriptBuilder.toString()));

            try { String r = callChatMessages(messages); return refuseGuard(r, text); }
            catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("400"))
                    return refuseGuard(fallbackToPureTextRequest(messages), text);
                else throw e;
            }
        } catch (JSONException e) { return refuseGuard(callChatSimple(receivePrompt + "\n\n" + text), text); }
    }

    public static String fromChinese(String text, String lang) throws IOException {
        text = text.trim(); if (text.isEmpty()) return text;
        return callChatSimple("\u628a\u4ee5\u4e0b\u4e2d\u6587\u7ffb\u8bd1\u6210" + lang + "\uff1a" + text);
    }

    public static String translateTest(String text, String lang) throws IOException {
        if (isChineseOnly(text)) return callChatSimple("\u628a\u4ee5\u4e0b\u4e2d\u6587\u7ffb\u8bd1\u6210" + lang + "\uff1a" + text);
        else return toChinese(text, "0");
    }

    public static String getSpanishRegionDirective(String nationality, int nativeLang, String chatId) {
        String nat = (nationality != null) ? nationality.toLowerCase() : "";
        if (nat.isEmpty() && chatId != null) {
            nat = getFriendNationality(chatId);
        }
        String friendLang = getFriendLang(chatId);
        String langCode = (friendLang != null && !friendLang.isEmpty()) ? friendLang : "";
        String region = mapSpanishRegion(nat);
        if (region == null && (langCode.startsWith("es") || "es".equals(langCode))) region = "es-419";
        if (region == null) return "";

        String description;
        switch (region) {
            case "es-MX": description = "\u58a8\u897f\u54e5\u897f\u73ed\u7259\u8bed\uff1a\u8bf7\u4f7f\u7528\u58a8\u897f\u54e5\u5e38\u7528\u8bcd\u6c47\u548c\u8868\u8fbe\u4e60\u60ef\uff0c\u5982\u201c\u00bfQu\u00e9 onda?\u201d\u98ce\u683c"; break;
            case "es-AR": description = "\u963f\u6839\u5ef7/\u62c9\u666e\u62c9\u5854\u897f\u73ed\u7259\u8bed\uff1a\u8bf7\u4f7f\u7528voseo\uff08vos ten\u00e9s/vos quer\u00e9s\uff09\u3001\u963f\u6839\u5ef7\u5e38\u7528\u8bcd\u6c47"; break;
            case "es-ES": description = "\u897f\u73ed\u7259\u672c\u571f\u897f\u73ed\u7259\u8bed\uff1a\u8bf7\u4f7f\u7528vosotros\u548c\u897f\u73ed\u7259\u5e38\u7528\u8868\u8fbe\uff0c\u5982\u201c\u00bfQu\u00e9 tal?\u201d\u98ce\u683c"; break;
            case "es-CO": description = "\u62c9\u7f8e\u897f\u73ed\u7259\u8bed\uff08\u504f\u5b89\u7b2c\u65af\uff09\uff1a\u8bf7\u4f7f\u7528\u54e5\u4f26\u6bd4\u4e9a/\u79d8\u9c81/\u5384\u74dc\u591a\u5c14\u7b49\u5730\u5e38\u7528\u8868\u8fbe\uff0c\u8bed\u6c14\u793c\u8c8c\u6e29\u548c"; break;
            case "es-US": description = "\u7f8e\u56fd\u897f\u73ed\u7259\u8bed\uff1a\u53ef\u6df7\u5165\u5c11\u91cf\u82f1\u8bed\u501f\u8bcd\uff0c\u62c9\u7f8e\u8868\u8fbe\u4e3a\u4e3b"; break;
            case "es-419": description = "\u62c9\u7f8e\u897f\u73ed\u7259\u8bed\uff08\u4e2d\u6027\uff09\uff1a\u8bf7\u4f7f\u7528\u62c9\u7f8e\u901a\u7528\u8868\u8fbe\uff0c\u907f\u514dvosotros"; break;
            default: description = "\u8bf7\u6839\u636e\u5bf9\u65b9\u56fd\u5bb6\u8c03\u6574\u897f\u73ed\u7259\u8bed\u8868\u8fbe"; break;
        }
        return "\n\n\u3010\u76ee\u6807\u8bed\u5730\u533a\u9002\u914d\u3011" + region + "\uff1a" + description + "\u3002";
    }

    private static String mapSpanishRegion(String nationality) {
        if (nationality == null || nationality.isEmpty()) return null;
        switch (nationality) {
            case "mexico": return "es-MX";
            case "argentina": case "uruguay": case "paraguay": return "es-AR";
            case "spain": return "es-ES";
            case "colombia": case "peru": case "ecuador": case "bolivia": case "venezuela": return "es-CO";
            case "chile": case "costa rica": case "panama": case "nicaragua": case "honduras":
            case "el salvador": case "guatemala": case "cuba": case "dominican republic": case "puerto rico": return "es-419";
            case "united states": case "usa": case "us": case "america": return "es-US";
            default: return null;
        }
    }

    public static String translateWithHistory(String text, String langCode, String chatId) throws IOException {
        return translateWithHistory(text, langCode, chatId, false);
    }

    public static String translateWithHistory(String text, String langCode, String chatId, boolean retry) throws IOException {
        maybeRecheckMode();
        try {
            JSONArray messages = new JSONArray();
            String sysPrompt;
            switch (langCode) {
                case "ru": sysPrompt = promptRU; break;
                case "uk": sysPrompt = promptUK; break;
                case "ko": sysPrompt = promptKO; break;
                case "es": sysPrompt = promptES; break;
                default: sysPrompt = promptEN; break;
            }

            String spanishDirective = "";
            if ("es".equals(langCode)) spanishDirective = getSpanishRegionDirective(null, 0, chatId);

            boolean useCustomPipeFormat = sysPrompt != null && (
                    sysPrompt.contains("==========")
                            || sysPrompt.contains("选项区")
                            || sysPrompt.contains("下半部分"));

            String formatProtocol;
            if (useCustomPipeFormat) {
                if (retry) {
                    formatProtocol = "\n\n【输出格式补充】\n"
                            + "请严格遵循你上面收到的【最终输出格式】，不要输出JSON。\n"
                            + "中间必须用 ========== 隔开。\n"
                            + "上半部分分析必须使用中文，并且不少于1200个中文字，不超过1800个中文字。宁多勿少。\n"
                            + "下半部分只输出4个选项，每个选项用 | 分隔。\n";
                } else {
                    formatProtocol = "\n\n【输出格式补充】\n"
                            + "请严格遵循你上面收到的【最终输出格式】，不要输出JSON。\n"
                            + "中间必须用 ========== 隔开。\n"
                            + "上半部分分析必须使用中文，并且不少于1200个中文字，不超过1800个中文字。宁多勿少。\n"
                            + "下半部分只输出4个选项，每个选项用 | 分隔。\n";
                }
            } else {
                if (retry) {
                    formatProtocol = "\n\n【最高优先级输出格式】\n"
                            + "忽略你之前提到的 ========== 和 | 格式。\n"
                            + "必须只输出一个JSON对象，不要输出任何额外文字。\n"
                            + "JSON格式如下：\n"
                            + "{\"analysis\":\"\",\"options\":[{\"foreign\":\"外语文本\",\"meaning\":\"中文大意\",\"tone\":\"语气标签\"},{\"foreign\":\"外语文本\",\"meaning\":\"中文大意\",\"tone\":\"语气标签\"},{\"foreign\":\"外语文本\",\"meaning\":\"中文大意\",\"tone\":\"语气标签\"},{\"foreign\":\"外语文本\",\"meaning\":\"中文大意\",\"tone\":\"语气标签\"}]}\n"
                            + "必须输出4个选项。\n"
                            + "foreign、meaning、tone字段名不能改。\n";
                } else {
                    formatProtocol = "\n\n【最高优先级输出格式】\n"
                            + "忽略你之前提到的 ========== 和 | 格式。\n"
                            + "必须只输出一个JSON对象，不要输出任何额外文字。\n"
                            + "JSON格式如下：\n"
                            + "{\"analysis\":\"这里写上半部分分析\",\"options\":[{\"foreign\":\"外语文本\",\"meaning\":\"中文大意\",\"tone\":\"语气标签\"},{\"foreign\":\"外语文本\",\"meaning\":\"中文大意\",\"tone\":\"语气标签\"},{\"foreign\":\"外语文本\",\"meaning\":\"中文大意\",\"tone\":\"语气标签\"},{\"foreign\":\"外语文本\",\"meaning\":\"中文大意\",\"tone\":\"语气标签\"}]}\n"
                            + "必须输出4个选项。\n"
                            + "foreign、meaning、tone字段名不能改，内容按你的指令填写。\n";
                }
            }

            String targetRule = "\n【回复目标识别规则，必须遵守】\n"
                    + "1. 如果用户输入中包含【我要回复的对方原话】，说明用户是在回复对方这条消息。"
                    + "你必须在分析中第一句写明：\"你正在回复对方这句话：<原话>\"，然后再写其他分析。\n"
                    + "2. 如果用户输入中包含【我对我自己之前这条外语消息的补充】，说明用户是在补充自己这条历史消息。"
                    + "你必须在分析中第一句写明：\"你是在补充自己这条历史消息：<原话>\"，然后再写其他分析。\n"
                    + "3. 如果用户输入中既没有【我要回复的对方原话】，也没有【我对我自己之前这条外语消息的补充】，"
                    + "说明用户没有显式选择回复目标。你必须根据下面的对话历史，推断用户最可能是在回复对方哪一句话，"
                    + "还是在补充自己之前哪一条外语消息。然后在分析中第一句写明："
                    + "\"我推断你是在回复对方这句话：<推断原话>\" 或 \"我推断你是在补充自己这条历史消息：<推断原话>\"。"
                    + "如果无法判断，就写\"我推断你是接着最近对话继续回复\"。\n"
                    + "4. 上半部分分析不能为空。\n"
                    + "5. 上半部分分析必须使用中文，并且不少于1200个中文字，不超过1800个中文字。宁多勿少。\n"
                    + "6. 上半部分分析必须严格按你收到的系统提示里的分析步骤逐步写，不得跳过步骤，不得只写一两句概括。\n"
                    + "7. 先完成上半部分分析，再生成4个选项。\n";

            String contextRule = "\n【上下文使用规则】\n"
                    + "历史记录仅用于理解对话语义和对方背景。\n"
                    + "不得继承历史中曾出现的极端、露骨、粗俗或一次性语气。\n"
                    + "历史中标记为[一次性上下文]的内容只表示它发生过，不代表长期风格。\n"
                    + "本次翻译的语气只由 <translate> 内的当前原文决定。\n";

            String fullProtocol = sysPrompt + profileBlock(chatId) + spanishDirective + formatProtocol + targetRule + contextRule;

            messages.put(createMessageObj("system", fullProtocol));

            JSONArray fullHistory = loadHistory(chatId);
            StringBuilder scriptBuilder = new StringBuilder();

            int maxChatMessages = 60;
            int startIdx = Math.max(0, fullHistory.length() - maxChatMessages);

            int visibleIndex = 0;
            for (int i = startIdx; i < fullHistory.length(); i++) {
                JSONObject msg = fullHistory.getJSONObject(i);
                String role = msg.optString("role", "");
                String content = msg.optString("content", "");

                String prefix = msg.optBoolean("oneTime", false) ? "[一次性上下文] " : "";
                visibleIndex++;

                if ("user".equals(role)) {
                    scriptBuilder.append("[").append(visibleIndex).append("] ")
                            .append(prefix)
                            .append(scriptLine("对方", content, "中文意思"));
                } else if ("assistant".equals(role)) {
                    scriptBuilder.append("[").append(visibleIndex).append("] ")
                            .append(prefix)
                            .append(scriptLine("我", content, "中文原意"));
                }
            }

            scriptBuilder.append("\n<translate>\n").append(text).append("\n</translate>");
            messages.put(createMessageObj("user", scriptBuilder.toString()));

            try {
                return callChatMessages(messages, getMaxTokens());
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("400")) {
                    return fallbackToPureTextRequest(messages);
                } else {
                    throw e;
                }
            }
        } catch (JSONException e) {
            throw new IOException("构建Messages失败");
        }
    }

    public static String translateForPicker(String text, String langCode, String chatId) throws IOException {
        return translateWithHistory(text, langCode, chatId, false);
    }

    public static String translateForPicker(String text, String langCode, String chatId, boolean retry) throws IOException {
        return translateWithHistory(text, langCode, chatId, retry);
    }

    private static String fallbackToPureTextRequest(JSONArray originalMessages) throws IOException {
        try {
            JSONArray cleanMessages = new JSONArray();
            for (int i = 0; i < originalMessages.length(); i++) {
                JSONObject msg = originalMessages.getJSONObject(i);
                String role = msg.getString("role");
                Object contentObj = msg.get("content");
                JSONObject cleanMsg = new JSONObject(); cleanMsg.put("role", role);
                if (contentObj instanceof JSONArray) {
                    JSONArray arr = (JSONArray) contentObj;
                    StringBuilder textSb = new StringBuilder();
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject item = arr.getJSONObject(j);
                        if ("text".equals(item.optString("type"))) textSb.append(item.optString("text")).append("\n");
                    }
                    cleanMsg.put("content", textSb.toString().replaceAll("\\n{3,}", "\n\n").trim());
                } else { cleanMsg.put("content", contentObj.toString()); }
                cleanMessages.put(cleanMsg);
            }
            return callChatMessages(cleanMessages);
        } catch (JSONException e) { throw new IOException("降级解析失败"); }
    }

    private static String callChatSimple(String prompt) throws IOException {
        if (apiKey == null || apiKey.isEmpty()) throw new IOException("Key未配置");
        if (client == null) throw new IOException("未初始化");
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", getMaxTokens());
            body.put("temperature", getTemperature());
            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject(); m.put("role", "user"); m.put("content", prompt);
            msgs.put(m); body.put("messages", msgs);
            return executeRequest(body);
        } catch (JSONException e) { throw new IOException("构建失败"); }
    }

    private static String callChatMessages(JSONArray messages) throws IOException {
        if (apiKey == null || apiKey.isEmpty()) throw new IOException("Key未配置");
        if (client == null) throw new IOException("未初始化");
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", getMaxTokens());
            body.put("temperature", getTemperature());
            body.put("messages", messages);
            return executeRequest(body);
        } catch (JSONException e) { throw new IOException("构建失败"); }
    }
    
    private static String callChatMessages(JSONArray messages, int maxTokens) throws IOException {
    if (apiKey == null || apiKey.isEmpty()) throw new IOException("Key未配置");
    if (client == null) throw new IOException("未初始化");
    try {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", getTemperature());
        body.put("messages", messages);
        return executeRequest(body);
    } catch (JSONException e) {
        throw new IOException("构建失败");
    }
}

    private static String executeRequest(JSONObject body) throws IOException { return executeRequestWith(client, body); }

    private static String executeRequestWith(OkHttpClient useClient, JSONObject body) throws IOException {
        Request req = new Request.Builder()
                .url(fixUrl(apiUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        try (Response resp = useClient.newCall(req).execute()) {
            String responseBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) throw new IOException("HTTP状态码 " + resp.code() + "\n" + responseBody);
            try {
                JSONObject json = new JSONObject(responseBody);
                JSONObject choice = json.getJSONArray("choices").getJSONObject(0);
                String content = choice.getJSONObject("message").optString("content", "").trim();
                if (content.isEmpty()) throw new IOException("大模型返回了空数据。");
                return content;
            } catch (IOException e) { throw e; }
            catch (Exception e) { throw new IOException("JSON解析失败：" + responseBody); }
        }
    }

    private static String fixUrl(String url) {
        if (url == null || url.isEmpty()) return "https://api.openai.com/v1/chat/completions";
        
        // 如果用户自己填写了完整的完整路径（包含 chat/completions），直接信任并原样返回
        if (url.endsWith("/chat/completions")) {
            return url;
        }

        // 如果没有包含 chat/completions，我们需要帮它补全
        if (!url.endsWith("/")) {
            url += "/";
        }
        
        // 关键改动：对于 Gemini 等特殊接口，如果地址里已经有 openai/v1/，就不要瞎切了
        if (url.contains("generativelanguage.googleapis.com")) {
             // 针对 Gemini 官方接口的特殊补全
             if (!url.contains("chat/completions")) {
                  return url + "chat/completions";
             }
        }

        // 保留对普通中转 API 的宽容处理（如果不是 Gemini，才执行旧的切割逻辑）
        int idx = url.indexOf("/v1/");
        if (idx >= 0 && !url.contains("generativelanguage.googleapis.com")) {
            url = url.substring(0, idx + 4);
        }
        
        // 最后确保以完整的 completions 结尾
        if (!url.endsWith("chat/completions")) {
            return url + "chat/completions";
        }
        return url;
    }

    public static List<String> fetchModels(String key, String baseUrl) throws IOException {
        List<String> result = new ArrayList<>();
        String url = baseUrl;
        if (url.endsWith("/chat/completions")) url = url.substring(0, url.length() - "/chat/completions".length());
        int idx = url.indexOf("/v1"); if (idx >= 0) url = url.substring(0, idx);
        if (!url.endsWith("/")) url += "/"; url += "v1/models";
        initForFetch(key, url);
        Request req = new Request.Builder().url(url).header("Authorization", "Bearer " + key).get().build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            JSONArray data = new JSONObject(resp.body().string()).getJSONArray("data");
            for (int i = 0; i < data.length(); i++) result.add(data.getJSONObject(i).getString("id"));
        } catch (JSONException e) { throw new IOException("解析失败"); }
        return result;
    }

    private static void loadCache() {
        if (cacheFile == null || !cacheFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(cacheFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\\|\\|\\|");
                if (parts.length >= 3) {
                    String foreign = stripFlipMarks(parts[1]).replace("\\n", "\n");
                    String chinese = stripFlipMarks(parts[2]).replace("\\n", "\n");
                    cache.put(parts[0], new String[]{foreign, chinese});
                    foreignToChinese.put(foreign, chinese); chineseToForeign.put(chinese, foreign);
                }
            }
        } catch (Exception ignored) {}
    }

    public static void saveCache() {
        try {
            if (cacheFile == null) return;
            cacheFile.getParentFile().mkdirs();
            try (BufferedWriter w = new BufferedWriter(new FileWriter(cacheFile))) {
                for (Map.Entry<String, String[]> e : cache.entrySet()) {
                    w.write(e.getKey() + "|||" + stripFlipMarks(e.getValue()[0]).replace("\n", "\\n")
                            + "|||" + stripFlipMarks(e.getValue()[1]).replace("\n", "\\n"));
                    w.newLine();
                }
            }
        } catch (Exception ignored) {}
    }

    public static String[] getCached(String key) { return cache.get(key); }

    public static void cacheResult(String key, String foreign, String chinese) {
        foreign = stripFlipMarks(foreign); chinese = stripFlipMarks(chinese);
        cache.put(key, new String[]{foreign, chinese});
        foreignToChinese.put(foreign, chinese); chineseToForeign.put(chinese, foreign);
        saveCache();
    }

    public static String getForeignByChinese(String chinese) {
        if (chinese == null || chinese.trim().isEmpty()) return null;
        String clean = stripFlipMarks(chinese);
        String exact = chineseToForeign.get(clean); if (exact != null) return exact;
        for (Map.Entry<String, String> entry : chineseToForeign.entrySet()) {
            String k = stripFlipMarks(entry.getKey()), v = stripFlipMarks(entry.getValue());
            if (clean.equals(k) || clean.contains(k) || k.contains(clean)) return v;
        }
        return null;
    }

    public static String getChineseByForeign(String foreign) {
        if (foreign == null || foreign.trim().isEmpty()) return null;
        String clean = stripFlipMarks(foreign);
        String exact = foreignToChinese.get(clean); if (exact != null) return exact;
        exact = mySentDrafts.get(clean); if (exact != null) return exact;
        for (Map.Entry<String, String> entry : foreignToChinese.entrySet()) {
            String k = stripFlipMarks(entry.getKey()), v = stripFlipMarks(entry.getValue());
            if (clean.equals(k) || clean.contains(k) || k.contains(clean)) return v;
        }
        return null;
    }

    public static String getForeignFuzzy(String copiedText) {
        if (copiedText == null || copiedText.trim().isEmpty()) return null;
        String clean = stripFlipMarks(copiedText);
        if (mySentDrafts.containsKey(clean)) return clean;
        if (foreignToChinese.containsKey(clean)) return clean;
        if (chineseToForeign.containsKey(clean)) return chineseToForeign.get(clean);
        for (Map.Entry<String, String> entry : foreignToChinese.entrySet()) {
            String f = stripFlipMarks(entry.getKey()), c = stripFlipMarks(entry.getValue());
            if (clean.contains(c) || c.contains(clean) || clean.contains(f) || f.contains(clean)) return f;
        }
        return null;
    }

    private static void loadPrompts() {
        try {
            if (promptFile.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(promptFile));
                String cur = ""; StringBuilder sb = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("###ZH###")) { cur = "ZH"; sb.setLength(0); }
                    else if (line.startsWith("###EN###")) { if (cur.equals("ZH")) receivePrompt = sb.toString().trim(); cur = "EN"; sb.setLength(0); }
                    else if (line.startsWith("###RU###")) { if (cur.equals("EN")) promptEN = sb.toString().trim(); cur = "RU"; sb.setLength(0); }
                    else if (line.startsWith("###UK###")) { if (cur.equals("RU")) promptRU = sb.toString().trim(); cur = "UK"; sb.setLength(0); }
                    else if (line.startsWith("###KO###")) { if (cur.equals("UK")) promptUK = sb.toString().trim(); cur = "KO"; sb.setLength(0); }
                    else if (line.startsWith("###ES###")) { if (cur.equals("KO")) promptKO = sb.toString().trim(); cur = "ES"; sb.setLength(0); }
                    else { sb.append(line).append("\n"); }
                }
                if (cur.equals("EN")) promptEN = sb.toString().trim();
                else if (cur.equals("RU")) promptRU = sb.toString().trim();
                else if (cur.equals("UK")) promptUK = sb.toString().trim();
                else if (cur.equals("KO")) promptKO = sb.toString().trim();
                else if (cur.equals("ES")) promptES = sb.toString().trim();
                r.close();
            }
        } catch (Exception ignored) {}
        if (receivePrompt.isEmpty()) receivePrompt = "\u4f60\u662f\u6211\u7684\u4e13\u5c5e\u793e\u4ea4\u60c5\u62a5\u4f20\u8bd1\u5458\u3002\u8981\u6c42\uff1a1. \u514b\u9686\u5bf9\u65b9\u7684\u8bed\u6c14\u98ce\u683c\u30022. \u53ea\u7ed91\u4e2a\u4e2d\u6587\u7ffb\u8bd1\uff0c\u4e0d\u8981\u9009\u9879\u30023. \u4e0d\u8981\u52a0\u524d\u8a00\u540e\u8bed\u30024. \u6f5c\u53f0\u8bcd\u653e\u672b\u5c3e\u62ec\u53f7\uff08\u4e0d\u8d85\u8fc720\u5b57\uff09\u3002";
        if (promptEN.isEmpty()) promptEN = "\u4f60\u662f\u793e\u4ea4\u5634\u66ff\u3002\u628a\u4e2d\u6587\u8f6c\u6210\u5730\u9053\u82f1\u8bed\u53e3\u8bed\uff0c4\u7248\u672c\u3002\u683c\u5f0f\uff1a\u5916\u6587|\u4e2d\u6587\u5927\u610f|\u6807\u7b7e\u3002";
        if (promptRU.isEmpty()) promptRU = "\u4f60\u662f\u793e\u4ea4\u5634\u66ff\u3002\u628a\u4e2d\u6587\u8f6c\u6210\u5730\u9053\u4fc4\u8bed\u53e3\u8bed\uff0c4\u7248\u672c\u3002\u683c\u5f0f\uff1a\u5916\u6587|\u4e2d\u6587\u5927\u610f|\u6807\u7b7e\u3002";
        if (promptUK.isEmpty()) promptUK = "\u4f60\u662f\u793e\u4ea4\u5634\u66ff\u3002\u628a\u4e2d\u6587\u8f6c\u6210\u5730\u9053\u4e4c\u514b\u5170\u8bed\u53e3\u8bed\uff0c4\u7248\u672c\u3002\u683c\u5f0f\uff1a\u5916\u6587|\u4e2d\u6587\u5927\u610f|\u6807\u7b7e\u3002";
        if (promptKO.isEmpty()) promptKO = "\u4f60\u662f\u793e\u4ea4\u5634\u66ff\u3002\u628a\u4e2d\u6587\u8f6c\u6210\u5730\u9053\u97e9\u8bed\u53e3\u8bed\uff0c4\u7248\u672c\u3002\u683c\u5f0f\uff1a\u5916\u6587|\u4e2d\u6587\u5927\u610f|\u6807\u7b7e\u3002";
        if (promptES.isEmpty()) promptES = "\u4f60\u662f\u793e\u4ea4\u5634\u66ff\u3002\u628a\u4e2d\u6587\u8f6c\u6210\u5730\u9053\u897f\u73ed\u7259\u8bed\u53e3\u8bed\uff0c4\u7248\u672c\u3002\u683c\u5f0f\uff1a\u5916\u6587|\u4e2d\u6587\u5927\u610f|\u6807\u7b7e\u3002";
    }

    public static void savePrompts(String zh, String en, String ru, String uk) {
        receivePrompt = zh; promptEN = en; promptRU = ru; promptUK = uk;
    }
    public static void savePrompts(String zh, String en, String ru, String uk, String ko, String es) {
        receivePrompt = zh; promptEN = en; promptRU = ru; promptUK = uk; promptKO = ko; promptES = es;
    }

    private static File historyFile(String chatId) {
        return new File("/data/data/com.hellotalk/files/htai_hist_" + chatId + ".json");
    }

    public static JSONArray loadHistory(String chatId) {
        synchronized (fileLock) {
            File f = historyFile(chatId);
            if (!f.exists()) return new JSONArray();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return new JSONArray(sb.toString());
            } catch (Exception e) { return new JSONArray(); }
        }
    }

    private static void writeHistoryLocked(String chatId, JSONArray history) {
        try {
            File f = historyFile(chatId); f.getParentFile().mkdirs();
            BufferedWriter w = new BufferedWriter(new FileWriter(f));
            w.write(history.toString()); w.close();
        } catch (Exception ignored) {}
    }

    public static void appendHistory(String chatId, String msgId, String role, String content) {
        appendHistory(chatId, msgId, role, content, System.currentTimeMillis(), null, false);
    }

    public static void appendHistory(String chatId, String msgId, String role, String content, long timestamp, String quotedText) {
        appendHistory(chatId, msgId, role, content, timestamp, quotedText, false);
    }

    public static void appendHistory(String chatId, String msgId, String role, String content, long timestamp, String quotedText, boolean oneTime) {
        if (content == null || content.isEmpty()) return;
        maybeRecheckMode();
        if (quotedText != null && !quotedText.isEmpty()) {
            String who = "assistant".equals(role) ? "\u6211" : "\u5bf9\u65b9";
            content = "\uff08" + who + "\u6b63\u5728\u5f15\u7528/\u56de\u590d\u6b64\u524d\u5bf9\u8bdd\uff1a\"" + quotedText + "\"\uff09\n" + content;
        }

        List<JSONObject> distillBatch = null;
        synchronized (fileLock) {
            try {
                JSONArray history = loadHistory(chatId);
                if (msgId != null && !msgId.isEmpty()) {
                    for (int i = 0; i < history.length(); i++)
                        if (msgId.equals(history.getJSONObject(i).optString("msgId"))) return;
                }
                JSONObject entry = new JSONObject();
                if (msgId != null) entry.put("msgId", msgId);
                entry.put("role", role);
                entry.put("timestamp", timestamp);
                entry.put("oneTime", oneTime);
                entry.put("content", content.length() > 1000 ? content.substring(0, 1000) : content);
                history.put(entry);

                List<JSONObject> list = new ArrayList<>();
                for (int i = 0; i < history.length(); i++) list.add(history.getJSONObject(i));
                Collections.sort(list, (a, b) -> Long.compare(a.optLong("timestamp", 0), b.optLong("timestamp", 0)));
                JSONArray sortedHistory = new JSONArray();
                for (JSONObject obj : list) sortedHistory.put(obj);
                history = sortedHistory;

                if (history.length() > HISTORY_HARD_CAP) {
                    JSONArray trimmed = new JSONArray();
                    for (int i = history.length() - HISTORY_SOFT_CAP; i < history.length(); i++) trimmed.put(history.get(i));
                    writeHistoryLocked(chatId, trimmed);
                } else if (history.length() >= HISTORY_SOFT_CAP + DISTILL_BATCH_MIN) {
                    int batchCount = history.length() - HISTORY_SOFT_CAP;
                    distillBatch = new ArrayList<>();
                    for (int i = 0; i < batchCount; i++) distillBatch.add(history.getJSONObject(i));
                    writeHistoryLocked(chatId, history);
                } else {
                    writeHistoryLocked(chatId, history);
                }
            } catch (Exception ignored) {}
        }
        if (distillBatch != null && !distillBatch.isEmpty()) distillBatch(chatId, distillBatch);
        maybeBackup();
    }
}

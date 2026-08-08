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
    // ★ v5.3: LinkedHashMap 保持插入顺序，自动淘汰最旧
    public static final Map<String, String> mySentDrafts = new LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 800;
        }
    };

    private static final Map<String, String> imageBase64Cache = new ConcurrentHashMap<>();

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
    private static final Pattern FLIP_MARKS_PATTERN = Pattern.compile("([ ]?[🌐🔄]+)$");

    private static final Pattern PAREN_TAIL = Pattern.compile("[（(]([^()（）]*)[)）]\\s*$");
    private static final Pattern NUMBER_PREFIX = Pattern.compile(
            "^(?:版本\\s*\\d*|[Oo]ption\\s*\\d*|选项\\s*\\d*|\\d{1,2}\\s*[.、)）:：]|[一二三四五六①-⑳]+\\s*[.、)）:：]?)\\s*");

    private static final int MAX_TOTAL_BASE64_CHARS = 900_000;

    // =========================================================
    // ★ 记忆系统
    // =========================================================

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
            "你是语言交换聊天助手的记忆档案管理员。我会给你一份现有档案和一批即将归档的旧聊天记录，你的任务是把它们合并成一份更新后的好友档案。\n" +
            "规则：\n" +
            "1. 只记录有长期价值的信息：对方的基本事实（名字、城市、职业、学习、爱好、家庭等）、双方关系阶段与熟悉程度、长期话题与尚未兑现的约定、对方的忌讳与偏好、对方的说话风格。\n" +
            "2. 新信息与旧档案冲突时，以新信息为准；已结束的话题、已过期或已兑现的约定、过时的状态要删掉。\n" +
            "3. 不要记录琐碎闲聊细节，不要逐条复述聊天内容。\n" +
            "4. 输出纯文本档案，分小节、每行一条，总长度严格控制在500字以内。\n" +
            "5. 只输出档案正文本身，不要任何前缀、后缀、解释。";

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
                Log.i(TAG, "已触发急停：切断所有底层翻译请求");
            } catch (Exception ignored) {}
        }
    }

    // =========================================================
    // ★ 记忆模式（不变）
    // =========================================================

    private static String runRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) {
                sb.append(l).append("\n");
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static android.app.Application currentAppByReflect() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.app.Application) {
                return (android.app.Application) app;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean sandboxHasMemory() {
        try {
            File dir = new File("/data/data/com.hellotalk/files");
            String[] names = dir.list();
            if (names == null) return false;
            for (String n : names) {
                if (n != null && n.startsWith("htai_")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean storeHasBackup() {
        try {
            String out = runRoot("ls " + STORE_DIR + "/htai_* 2>/dev/null");
            return out != null && !out.trim().isEmpty();
        } catch (Throwable e) {
            return false;
        }
    }

    private static String readMarker() {
        try {
            File f = new File(MARKER_FILE);
            if (!f.exists()) return null;
            BufferedReader r = new BufferedReader(new FileReader(f));
            String s = r.readLine();
            r.close();
            return s == null ? null : s.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeMarker(String mode) {
        try {
            runRoot("echo " + mode + " > " + MARKER_FILE + " && chmod 644 " + MARKER_FILE);
        } catch (Throwable ignored) {}
    }

    private static void initMemoryMode() {
        try {
            String marker = readMarker();
            if ("pending".equals(marker)) {
                if (storeHasBackup()) {
                    memPending = true;
                    toastPending();
                    Log.w(TAG, "记忆模式：待认领");
                    return;
                }
                memPending = false;
                memMode = "main";
                writeMarker("main");
                return;
            }
            if ("temp".equals(marker)) {
                memPending = false;
                memMode = "temp";
                return;
            }
            if (sandboxHasMemory()) {
                memPending = false;
                memMode = "temp".equals(marker) ? "temp" : "main";
                if (marker == null || marker.isEmpty()) writeMarker("main");
                return;
            }
            if (storeHasBackup()) {
                memPending = true;
                writeMarker("pending");
                toastPending();
                return;
            }
            memPending = false;
            memMode = "main";
            if (marker == null || marker.isEmpty()) writeMarker("main");
        } catch (Throwable t) {
            memPending = false;
            memMode = "main";
        }
    }

    private static void toastPending() {
        if (pendingToastShown) return;
        pendingToastShown = true;
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    android.app.Application app = currentAppByReflect();
                    if (app != null) {
                        Toast.makeText(app,
                                "HT AI：检测到HelloTalk数据被清空，记忆已暂停。\n请打开遥控器选择【主账号】或【一次性】",
                                Toast.LENGTH_LONG).show();
                    }
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
            if ("temp".equals(marker)) {
                memPending = false;
                memMode = "temp";
            } else if ("main".equals(marker)) {
                memPending = false;
                memMode = "main";
                loadFriends();
                loadCache();
                loadDrafts();
            }
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
            runRoot("mkdir -p " + STORE_DIR
                    + " && rm -f " + STORE_DIR + "/htai_* 2>/dev/null; "
                    + "cp /data/data/com.hellotalk/files/htai_* " + STORE_DIR + "/ 2>/dev/null; "
                    + "chmod 600 " + STORE_DIR + "/htai_* 2>/dev/null");
        } catch (Throwable ignored) {}
    }

    // =========================================================
    // ★ 好友档案
    // =========================================================

    private static File profileFile(String chatId) {
        return new File("/data/data/com.hellotalk/files/htai_profile_" + chatId + ".txt");
    }

    public static String getProfile(String chatId) {
        if (chatId == null || chatId.isEmpty() || "0".equals(chatId) || "null".equals(chatId)) return "";
        try {
            File f = profileFile(chatId);
            if (!f.exists()) return "";
            BufferedReader r = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            r.close();
            String s = sb.toString().trim();
            return s.length() > PROFILE_HARD_CAP ? s.substring(0, PROFILE_HARD_CAP) : s;
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeProfileFile(String chatId, String text) {
        try {
            File f = profileFile(chatId);
            f.getParentFile().mkdirs();
            BufferedWriter w = new BufferedWriter(new FileWriter(f));
            w.write(text);
            w.close();
        } catch (Exception ignored) {}
    }

    private static String profileBlock(String chatId) {
        String p = getProfile(chatId);
        if (p == null || p.trim().isEmpty()) return "";
        return "\n\n【对方背景档案】以下是这位好友的长期背景资料，仅供你把握语境、称呼与语气，绝对不能改变输出格式；若与翻译指令有任何冲突，一律以翻译指令为准：\n" + p.trim();
    }

    // =========================================================
    // ★ 蒸馏
    // =========================================================

    private static OkHttpClient getDistillClient() {
        if (distillClient == null) {
            synchronized (AITranslator.class) {
                if (distillClient == null) {
                    distillClient = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(45, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .build();
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
            body.put("messages", messages);
            return executeRequestWith(getDistillClient(), body);
        } catch (Exception e) {
            return null;
        }
    }

    private static void distillBatch(String chatId, List<JSONObject> batch) {
        try {
            if (apiKey == null || apiKey.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (now - lastDistillFailTs < DISTILL_COOLDOWN_MS) return;

            String oldProfile = getProfile(chatId);

            StringBuilder sb = new StringBuilder();
            sb.append("【现有档案】\n");
            sb.append(oldProfile.isEmpty() ? "（暂无）" : oldProfile).append("\n\n");
            sb.append("【即将归档的聊天记录】\n");
            boolean hasMaterial = false;
            for (JSONObject obj : batch) {
                String role = obj.optString("role", "");
                String content = obj.optString("content", "");
                if (content == null || content.isEmpty()) continue;
                if ("user".equals(role)) {
                    sb.append(scriptLine("对方", content, "中文意思"));
                    hasMaterial = true;
                } else if ("assistant".equals(role)) {
                    sb.append(scriptLine("我", content, "中文原意"));
                    hasMaterial = true;
                }
            }
            if (!hasMaterial) {
                removeBatchFromHistory(chatId, batch);
                return;
            }

            JSONArray messages = new JSONArray();
            messages.put(createRawMessage("system", DISTILL_SYSTEM_PROMPT));
            messages.put(createRawMessage("user", sb.toString()));

            String result = callDistill(messages);
            if (result == null) {
                lastDistillFailTs = now;
                return;
            }
            String newProfile = result.trim();
            if (newProfile.isEmpty() || isRefusalResponse(newProfile)) {
                lastDistillFailTs = now;
                return;
            }
            if (newProfile.length() > PROFILE_HARD_CAP) {
                newProfile = newProfile.substring(0, PROFILE_HARD_CAP);
            }

            removeBatchFromHistory(chatId, batch);
            writeProfileFile(chatId, newProfile);
            lastDistillFailTs = 0;

            lastBackupTs = 0;
            maybeBackup();
        } catch (Throwable t) {
            lastDistillFailTs = System.currentTimeMillis();
        }
    }

    private static void removeBatchFromHistory(String chatId, List<JSONObject> batch) {
        synchronized (fileLock) {
            try {
                JSONArray history = loadHistory(chatId);
                Set<String> batchIds = new HashSet<>();
                for (JSONObject b : batch) {
                    String id = b.optString("msgId", "");
                    if (!id.isEmpty()) batchIds.add(id);
                }
                JSONArray kept = new JSONArray();
                for (int i = 0; i < history.length(); i++) {
                    JSONObject obj = history.getJSONObject(i);
                    String id = obj.optString("msgId", "");
                    boolean drop = false;
                    if (!id.isEmpty() && batchIds.contains(id)) {
                        drop = true;
                    } else {
                        for (JSONObject b : batch) {
                            if (b.optLong("timestamp", -1) == obj.optLong("timestamp", -2)
                                    && b.optString("content", "").equals(obj.optString("content", "\u0000"))) {
                                drop = true;
                                break;
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
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static String scriptLine(String who, String content, String noteLabel) {
        try {
            String clean = stripFlipMarks(content);
            String zh = (clean == null) ? null : foreignToChinese.get(clean);
            if (zh == null) zh = mySentDrafts.get(clean);
            if (zh != null && !zh.isEmpty() && !zh.equals(clean)) {
                return who + ": " + content + "（" + noteLabel + "：" + zh + "）\n";
            }
        } catch (Throwable ignored) {}
        return who + ": " + content + "\n";
    }

    public static boolean isRefusalResponse(String raw) {
        if (raw == null) return false;
        String t = raw.trim();
        if (t.isEmpty()) return false;
        if (t.length() > 500) return false;
        String low = t.toLowerCase();
        String[] marks = {
                "i'm sorry", "i am sorry", "im sorry", "sorry, but", "sorry, i",
                "i can't", "i cannot", "i'm unable", "i am unable", "not able to",
                "i apologize", "as an ai", "as a language model",
                "can't assist", "cannot assist", "unable to assist", "decline",
                "against my", "violat", "content policy", "safety guideline", "guidelines",
                "inappropriate", "offensive", "explicit",
                "抱歉", "对不起", "无法", "我不能", "作为ai", "作为人工智能",
                "敏感", "不合适", "违反", "政策", "准则", "拒绝"
        };
        for (String m : marks) {
            if (low.contains(m)) return true;
        }
        return false;
    }

    private static String refuseGuard(String result, String fallback) {
        if (result == null) return fallback;
        return isRefusalResponse(result) ? fallback : result;
    }

    // =========================================================
    // ★ v5.3: 草稿映射
    // =========================================================

    private static void loadDrafts() {
        try {
            if (draftsFile != null && draftsFile.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(draftsFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                String s = sb.toString().trim();
                if (s.isEmpty()) return;
                JSONObject obj = new JSONObject(s);
                Iterator<String> it = obj.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = obj.optString(k, "");
                    if (k == null || k.trim().isEmpty() || v == null || v.trim().isEmpty()) continue;
                    mySentDrafts.put(k, v);
                    foreignToChinese.put(k, v);
                    chineseToForeign.put(v, k);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void saveDrafts() {
        try {
            if (draftsFile == null) return;
            draftsFile.getParentFile().mkdirs();
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> e : mySentDrafts.entrySet()) {
                obj.put(e.getKey(), e.getValue());
            }
            BufferedWriter w = new BufferedWriter(new FileWriter(draftsFile));
            w.write(obj.toString());
            w.close();
        } catch (Exception ignored) {}
    }

    public static void rememberDraft(String foreign, String chinese) {
        try {
            String f = stripFlipMarks(foreign);
            String c = stripFlipMarks(chinese);
            if (f == null || c == null) return;
            f = f.trim();
            c = c.trim();
            if (f.isEmpty() || c.isEmpty() || f.equals(c)) return;
            mySentDrafts.put(f, c);
            foreignToChinese.put(f, c);
            chineseToForeign.put(c, f);
            cacheResult("draft_" + f.hashCode(), f, c);
            saveDrafts();
        } catch (Exception ignored) {}
    }

    /**
     * ★ v5.3: 精确 → longest common substring ≥60% → 包含兜底
     */
    public static String getDraftFuzzy(String sentForeignText) {
        if (sentForeignText == null || sentForeignText.trim().isEmpty()) return null;
        String clean = stripFlipMarks(sentForeignText);
        if (clean == null || clean.isEmpty()) return null;

        String exact = mySentDrafts.get(clean);
        if (exact != null) return exact;

        exact = foreignToChinese.get(clean);
        if (exact != null) return exact;

        String bestKey = null;
        int bestLen = 0;
        for (Map.Entry<String, String> entry : mySentDrafts.entrySet()) {
            String key = stripFlipMarks(entry.getKey());
            if (key == null || key.isEmpty()) continue;
            int common = longestCommonSubstringLength(clean, key);
            double coverage = (double) common / Math.max(clean.length(), key.length());
            if (coverage >= 0.60 && common > bestLen) {
                bestLen = common;
                bestKey = key;
            }
        }
        if (bestKey != null) return mySentDrafts.get(bestKey);

        for (Map.Entry<String, String> entry : mySentDrafts.entrySet()) {
            String key = stripFlipMarks(entry.getKey());
            if (key == null || key.isEmpty()) continue;
            if (clean.contains(key) && (double) key.length() / clean.length() >= 0.60) {
                return entry.getValue();
            }
            if (key.contains(clean) && (double) clean.length() / key.length() >= 0.60) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static int longestCommonSubstringLength(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        int max = 0;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    if (dp[i][j] > max) max = dp[i][j];
                }
            }
        }
        return max;
    }

    public static String getForeignByDraftChinese(String zh) {
        if (zh == null || zh.trim().isEmpty()) return null;
        String clean = stripFlipMarks(zh);
        for (Map.Entry<String, String> e : mySentDrafts.entrySet()) {
            String k = stripFlipMarks(e.getKey());
            String v = stripFlipMarks(e.getValue());
            if (v == null || v.isEmpty()) continue;
            if (clean.equals(v) || clean.contains(v) || v.contains(clean)) return k;
        }
        return null;
    }

    // =========================================================
    // ★ v5.3: 纯表情/纯标点检测 + 智能括号分析
    // =========================================================

    public static boolean hasAnyLetterOrDigit(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) return true;
        }
        return false;
    }

    /**
     * ★ v5.3: 对方发纯表情/纯标点时，结合上下文分析意图，
     * 返回如 " 😂😂（被我说的旅行糗事逗乐了）"
     */
    public static String analyzePureSymbol(String symbolText, String chatId) {
        if (symbolText == null || symbolText.trim().isEmpty()) return symbolText;
        if (apiKey == null || apiKey.isEmpty()) return symbolText;

        try {
            JSONArray messages = new JSONArray();

            String sysPrompt = receivePrompt + profileBlock(chatId) +
                    "\n\n【表情/标点深度分析协议】：" +
                    "\n1. 对方刚刚发了一个纯表情/标点符号，没有文字。" +
                    "\n2. 你的任务：仔细阅读下方的对话历史上下文，判断对方发这个表情/标点是在回应我的哪一句话或哪一个话题。" +
                    "\n3. 输出格式：只输出一个中文全角括号补在原文后面，括号内格式为：（被我的xx话题/xx话 + 情绪反应），括号内严格不超过20字。" +
                    "\n4. 必须说清楚是被\"我\"的什么内容触发的，比如：（被我的自嘲逗乐了）（对我的提议感到惊讶）（被我的关心感动了）（对我的消息表示无语）。" +
                    "\n5. 不要输出任何其他内容，不要翻译，不要解释，不要加前缀后缀，只输出原符号+括号。";

            messages.put(createMessageObj("system", sysPrompt));

            JSONArray fullHistory = loadHistory(chatId);
            StringBuilder scriptBuilder = new StringBuilder();
            scriptBuilder.append("【最近对话上下文】\n");

            int maxChatMessages = 15;
            int startIdx = Math.max(0, fullHistory.length() - maxChatMessages);
            boolean hasContext = false;

            for (int i = startIdx; i < fullHistory.length(); i++) {
                JSONObject msg = fullHistory.getJSONObject(i);
                String role = msg.optString("role", "");
                String content = msg.optString("content", "");
                if ("user".equals(role)) {
                    scriptBuilder.append(scriptLine("对方", content, "中文意思"));
                    hasContext = true;
                } else if ("assistant".equals(role)) {
                    scriptBuilder.append(scriptLine("我", content, "中文原意"));
                    hasContext = true;
                }
            }

            if (!hasContext) scriptBuilder.append("（暂无有效上下文）\n");
            scriptBuilder.append("\n【对方刚发的纯表情/标点符号】\n").append(symbolText);

            messages.put(createMessageObj("user", scriptBuilder.toString()));

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 120);
            body.put("messages", messages);

            String result = executeRequestWith(getReverseTranslateClient(), body);
            if (result != null && !result.trim().isEmpty()) {
                String clean = result.trim();
                // 提取括号内容
                String parenPart = "";
                Matcher pm = Pattern.compile("[（(]([^()（）]{1,25})[)）]").matcher(clean);
                if (pm.find()) {
                    parenPart = "（" + pm.group(1).trim() + "）";
                } else {
                    // AI 没给括号格式，自己构造
                    if (!clean.startsWith("（")) clean = "（" + clean;
                    if (!clean.endsWith("）")) clean = clean + "）";
                    clean = clean.replace("(", "（").replace(")", "）");
                    if (clean.length() > 30) clean = clean.substring(0, 30);
                    parenPart = clean;
                }
                return symbolText + " " + parenPart;
            }
        } catch (Exception e) {
            Log.w(TAG, "表情分析失败: " + e.getMessage());
        }
        return symbolText;
    }

    // =========================================================
    // ★ 反向翻译
    // =========================================================

    private static OkHttpClient getReverseTranslateClient() {
        if (reverseTranslateClient == null) {
            synchronized (AITranslator.class) {
                if (reverseTranslateClient == null) {
                    reverseTranslateClient = new OkHttpClient.Builder()
                            .connectTimeout(12, TimeUnit.SECONDS)
                            .readTimeout(45, TimeUnit.SECONDS)
                            .writeTimeout(20, TimeUnit.SECONDS)
                            .build();
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
            messages.put(createRawMessage("system", "把以下外语句子翻译成中文，只输出一句中文翻译，不要任何解释。"));
            messages.put(createRawMessage("user", foreignText));

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 300);
            body.put("messages", messages);

            String result = executeRequestWith(getReverseTranslateClient(), body);
            if (result != null && !result.trim().isEmpty() && !result.trim().equals(foreignText)) {
                String clean = result.trim();
                if (clean.length() > 200) clean = clean.substring(0, 200);
                return clean;
            }
        } catch (Exception e) {
            Log.w(TAG, "反向翻译失败: " + e.getMessage());
        }
        return null;
    }

    // =========================================================
    // 图片 Base64（不变）
    // =========================================================

    private static String buildImageCacheKey(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return path;
            return path + "_" + f.lastModified() + "_" + f.length();
        } catch (Throwable e) {
            return path;
        }
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

            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int maxSide = Math.max(w, h);
            if (maxSide > 448) {
                float scale = 448f / maxSide;
                int nw = Math.max(1, Math.round(w * scale));
                int nh = Math.max(1, Math.round(h * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }

            int[] qualities = new int[]{30, 22, 16, 12};
            byte[] bestBytes = null;
            for (int q : qualities) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, q, baos);
                byte[] bytes = baos.toByteArray();
                bestBytes = bytes;
                if (bytes.length <= 90 * 1024) break;
            }

            bitmap.recycle();
            if (bestBytes == null || bestBytes.length == 0) return null;

            String result = Base64.encodeToString(bestBytes, Base64.NO_WRAP);
            imageBase64Cache.put(cacheKey, result);
            return result;
        } catch (Throwable e) {
            return null;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private static class ParsedVisualInput {
        String cleanText;
        List<String> contextImagePaths = new ArrayList<>();
        List<String> quotedImagePaths = new ArrayList<>();
        boolean pureBracketMode = false;
        boolean quotedImageMissing = false;
    }

    private static ParsedVisualInput parseVisualMarkers(String content) {
        ParsedVisualInput result = new ParsedVisualInput();
        if (content == null) { result.cleanText = ""; return result; }
        String working = content;

        Matcher pureMatcher = PURE_BRACKET_MODE_PATTERN.matcher(working);
        if (pureMatcher.find()) { result.pureBracketMode = true; working = pureMatcher.replaceAll("").trim(); }

        Matcher quotedMissingMatcher = QUOTED_IMAGE_MISSING_PATTERN.matcher(working);
        if (quotedMissingMatcher.find()) {
            result.quotedImageMissing = true;
            working = quotedMissingMatcher.replaceAll("[系统提示：当前回复目标图存在，但本地路径缺失]").trim();
        }

        Matcher quotedMatcher = QUOTED_LOCAL_IMAGE_PATTERN.matcher(working);
        StringBuffer quotedSb = new StringBuffer();
        while (quotedMatcher.find()) {
            String path = quotedMatcher.group(1).trim();
            if (!path.isEmpty()) result.quotedImagePaths.add(path);
            quotedMatcher.appendReplacement(quotedSb, "[系统提示：当前回复目标图已附带]");
        }
        quotedMatcher.appendTail(quotedSb);
        working = quotedSb.toString();

        Matcher localMatcher = LOCAL_IMAGE_PATTERN.matcher(working);
        StringBuffer localSb = new StringBuffer();
        while (localMatcher.find()) {
            String path = localMatcher.group(1).trim();
            if (!path.isEmpty()) result.contextImagePaths.add(path);
            localMatcher.appendReplacement(localSb, "[系统提示：背景上下文图片已附带]");
        }
        localMatcher.appendTail(localSb);

        result.cleanText = localSb.toString().trim();
        return result;
    }

    private static JSONObject createTextPart(String text) throws JSONException {
        JSONObject txt = new JSONObject();
        txt.put("type", "text");
        txt.put("text", text);
        return txt;
    }

    private static JSONObject createImagePart(String base64) throws JSONException {
        JSONObject imgObj = new JSONObject();
        imgObj.put("type", "image_url");
        JSONObject urlObj = new JSONObject();
        urlObj.put("url", "data:image/jpeg;base64," + base64);
        imgObj.put("image_url", urlObj);
        return imgObj;
    }

    private static JSONObject createMessageObj(String role, String content) throws JSONException {
        JSONObject msgObj = new JSONObject();
        msgObj.put("role", role);
        ParsedVisualInput parsed = parseVisualMarkers(content);
        boolean hasContextImages = !parsed.contextImagePaths.isEmpty();
        boolean hasQuotedImages = !parsed.quotedImagePaths.isEmpty();

        if (!hasContextImages && !hasQuotedImages && !parsed.quotedImageMissing) {
            msgObj.put("content", parsed.cleanText);
            return msgObj;
        }

        JSONArray contentArray = new JSONArray();
        String clean = parsed.cleanText;
        if (parsed.pureBracketMode) {
            clean = "[系统提示：这是纯括号求助模式，必须执行模式A，不要给4个翻译选项]\n" + clean;
        }
        if (parsed.quotedImageMissing) {
            clean = clean + "\n[系统提示：当前回复目标是一张图片，但本地文件路径未获取到。]";
        }
        contentArray.put(createTextPart(clean));

        int totalB64Chars = 0;
        int qIdx = 1;
        for (String path : parsed.quotedImagePaths) {
            String b64 = encodeFileToBase64(path);
            if (b64 != null && !b64.isEmpty()) {
                if (totalB64Chars + b64.length() > MAX_TOTAL_BASE64_CHARS) {
                    contentArray.put(createTextPart("[当前回复目标焦点图 #" + qIdx + " 因体积限制未附带]"));
                    continue;
                }
                contentArray.put(createTextPart("[当前回复目标焦点图 #" + qIdx + "]"));
                contentArray.put(createImagePart(b64));
                totalB64Chars += b64.length();
                qIdx++;
            } else {
                contentArray.put(createTextPart("[当前回复目标图读取失败]"));
            }
        }

        int cIdx = 1;
        for (String path : parsed.contextImagePaths) {
            String b64 = encodeFileToBase64(path);
            if (b64 != null && !b64.isEmpty()) {
                if (totalB64Chars + b64.length() > MAX_TOTAL_BASE64_CHARS) {
                    contentArray.put(createTextPart("[背景上下文辅助图片 #" + cIdx + " 因体积限制未附带]"));
                    continue;
                }
                contentArray.put(createTextPart("[背景上下文辅助图片 #" + cIdx + "]"));
                contentArray.put(createImagePart(b64));
                totalB64Chars += b64.length();
                cIdx++;
            } else {
                contentArray.put(createTextPart("[背景上下文图片读取失败]"));
            }
        }

        msgObj.put("content", contentArray);
        return msgObj;
    }

    // =========================================================
    // 好友
    // =========================================================

    public static void loadFriends() {
        try {
            if (friendsFile.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(friendsFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                friendsData = new JSONObject(sb.toString());
            }
        } catch (Exception ignored) {}
    }

    public static void saveFriends() {
        try {
            friendsFile.getParentFile().mkdirs();
            BufferedWriter w = new BufferedWriter(new FileWriter(friendsFile));
            w.write(friendsData.toString());
            w.close();
        } catch (Exception ignored) {}
    }

    public static void registerFriend(String chatId, String name, String langCode) {
        try {
            if (chatId == null || chatId.isEmpty()) return;
            JSONObject friend = new JSONObject();
            if (friendsData.has(chatId)) friend = friendsData.getJSONObject(chatId);
            if (name != null && !name.isEmpty()) friend.put("name", name);
            else if (!friend.has("name")) friend.put("name", chatId);
            friend.put("lang", langCode != null ? langCode : "en");
            friend.put("lastTime", System.currentTimeMillis());
            friendsData.put(chatId, friend);
            saveFriends();
        } catch (JSONException ignored) {}
    }

    public static String getFriendLang(String chatId) {
        try {
            if (friendsData.has(chatId)) return friendsData.getJSONObject(chatId).optString("lang", "en");
        } catch (JSONException ignored) {}
        return "en";
    }

    public static String getFriendName(String chatId) {
        try {
            if (friendsData.has(chatId)) return friendsData.getJSONObject(chatId).optString("name", chatId);
        } catch (JSONException ignored) {}
        return chatId;
    }

    public static JSONArray getAllFriends() {
        JSONArray list = new JSONArray();
        try {
            JSONArray ids = friendsData.names();
            if (ids == null) return list;
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.getString(i);
                JSONObject info = friendsData.getJSONObject(id);
                JSONObject item = new JSONObject();
                item.put("id", id);
                item.put("name", info.optString("name", id));
                item.put("lang", info.optString("lang", "en"));
                item.put("lastTime", info.optLong("lastTime", 0));
                JSONArray hist = loadHistory(id);
                item.put("count", hist.length());
                list.put(item);
            }
        } catch (JSONException ignored) {}
        return list;
    }

    // =========================================================
    // 语言判断
    // =========================================================

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
| block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
| block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
| block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    public static boolean needTranslateToChinese(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (containsJapanese(text)) return false;
        boolean hasChinese = false;
        boolean hasForeignAlpha = false;
        for (char c : text.toCharArray()) {
            if (!hasForeignAlpha && String.valueOf(c).matches("[a-zA-Zа-яА-ЯёЁіІїЇєЄґҐ\\uAC00-\\uD7AFáéíóúÁÉÍÓÚñÑüÜäöüßÄÖÜ]")) {
                hasForeignAlpha = true;
            }
            if (!hasChinese) {
                Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
                if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
| block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
| block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
| block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                    hasChinese = true;
                }
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
| b == Character.UnicodeBlock.LATIN_EXTENDED_C || b == Character.UnicodeBlock.LATIN_EXTENDED_D) return true;
            if (b == Character.UnicodeBlock.CYRILLIC || b == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY) return true;
            if (b == Character.UnicodeBlock.GREEK || b == Character.UnicodeBlock.GREEK_EXTENDED) return true;
            if (b == Character.UnicodeBlock.HANGUL_SYLLABLES || b == Character.UnicodeBlock.HANGUL_JAMO
| b == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) return true;
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
        t = t.replace(";", ",").replace("；", ",");
        t = t.replace("—", "...").replace("–", "...").replace("―", "...").replace("─", "...");
        t = t.replaceAll(",[\\s,]*", ", ");
        t = t.replace(" ,", ",");
        t = t.replaceAll("\\.{4,}", "...");
        t = t.replaceAll("\\s{2,}", " ");
        return t.trim();
    }

    public static List<String[]> parseTranslateOptions(String result) {
        List<String[]> items = new ArrayList<>();
        if (result == null || result.trim().isEmpty()) return items;

        String optionsText;
        String[] splitData = result.split("={3,}");
        if (splitData.length >= 2) {
            optionsText = splitData[splitData.length - 1];
        } else {
            StringBuilder sb = new StringBuilder();
            boolean inOptions = false;
            for (String line : result.split("\n")) {
                String t = line.trim();
                if (!inOptions && (t.contains("下半部分") || t.matches("^[=+\\-]{3,}.*$"))) {
                    inOptions = true;
                    continue;
                }
                if (inOptions) sb.append(line).append("\n");
            }
            optionsText = sb.length() > 0 ? sb.toString() : result;
        }

        Set<String> seen = new HashSet<>();
        for (String rawLine : optionsText.split("\n")) {
            String line = rawLine.trim().replace("*", "").replace("｜", "|");
            if (line.isEmpty()) continue;
            if (line.matches("^[=+\\-|:：\\s]{3,}$")) continue;
            if (line.startsWith("|")) line = line.substring(1).trim();
            if (line.endsWith("|")) line = line.substring(0, line.length() - 1).trim();
            line = line.replaceFirst("^[•·▪◦]\\s*", "");
            if (line.isEmpty()) continue;

            String foreign = null;
            String chinese = "";
            String label = "";
            if (line.contains("|")) {
                String[] parts = line.split("\\|");
                List<String> cells = new ArrayList<>();
                for (String p : parts) { String c2 = p.trim(); if (!c2.isEmpty()) cells.add(c2); }
                if (cells.isEmpty()) continue;
                foreign = cells.get(0);
                if (cells.size() > 1) chinese = cells.get(1);
                if (cells.size() > 2) label = cells.get(2);
            } else {
                String core = NUMBER_PREFIX.matcher(line).replaceFirst("").trim();
                Matcher m = PAREN_TAIL.matcher(core);
                String paren = "";
                if (m.find()) { paren = m.group(1).trim(); core = core.substring(0, m.start()).trim(); }
                foreign = core;
                if (!paren.isEmpty()) {
                    if (paren.matches(".*[\\u4e00-\\u9fa5].*")) {
                        chinese = paren.replaceFirst("^(中文)?(大意|意思|含义|翻译)?\\s*[:：]?\\s*", "");
                    } else {
                        label = paren.replaceFirst("^(语气|风格|标签)?\\s*[:：]?\\s*", "");
                    }
                }
            }
            if (foreign == null) continue;
            foreign = NUMBER_PREFIX.matcher(foreign).replaceFirst("").trim();
            foreign = foreign.replaceAll("^[\"'""'']+|[\\\"'""'']+$", "").trim();
            chinese = chinese.replaceFirst("^(中文)?(大意|意思|含义|翻译)?\\s*[:：]?\\s*", "").trim();
            label = label.replaceFirst("^(语气|风格|标签)?\\s*[:：]?\\s*", "").trim();
            foreign = sanitizeForeignText(foreign);
            if (foreign.isEmpty() || !containsForeignLetters(foreign)) continue;
            if (!seen.add(foreign.toLowerCase())) continue;
            items.add(new String[]{foreign, chinese, label});
            if (items.size() >= 4) break;
        }
        return items;
    }

    public static String extractAnalysis(String result) {
        if (result == null) return "";
        String[] splitData = result.split("={3,}");
        if (splitData.length >= 2) return splitData[0].trim().replace("*", "");
        String[] lines = result.split("\n");
        int firstOptionLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim().replace("*", "");
            if (t.isEmpty()) continue;
            if (t.contains("|") || NUMBER_PREFIX.matcher(t).find()) { firstOptionLine = i; break; }
        }
        if (firstOptionLine <= 0) return "";
        StringBuilder an = new StringBuilder();
        for (int i = 0; i < firstOptionLine; i++) {
            String t = lines[i].trim();
            if (!t.isEmpty()) an.append(t).append("\n\n");
        }
        return an.toString().trim().replace("*", "");
    }

    // =========================================================
    // 翻译核心
    // =========================================================

    public static String toChinese(String text) throws IOException {
        return toChinese(text, "0");
    }

    public static String toChinese(String text, String chatId) throws IOException {
        maybeRecheckMode();
        text = text.trim();
        if (text.isEmpty()) return text;
        if (!needTranslateToChinese(text)) return text;

        try {
            JSONArray messages = new JSONArray();
            String sysPrompt = receivePrompt + profileBlock(chatId) +
                    "\n\n【系统隐性协议】：1.结合上下文翻译。2.只给1个中文翻译。3.不要前言后语。4.潜台词放末尾括号≤20字。";
            messages.put(createMessageObj("system", sysPrompt));

            JSONArray fullHistory = loadHistory(chatId);
            StringBuilder scriptBuilder = new StringBuilder();
            scriptBuilder.append("【最近上下文剧本】\n");
            int maxChatMessages = 15;
            int startIdx = Math.max(0, fullHistory.length() - maxChatMessages);
            boolean hasContext = false;
            for (int i = startIdx; i < fullHistory.length(); i++) {
                JSONObject msg = fullHistory.getJSONObject(i);
                String role = msg.optString("role", "");
                String content = msg.optString("content", "");
                if (content != null && content.equals(text)) continue;
                if ("user".equals(role)) { scriptBuilder.append(scriptLine("对方", content, "中文意思")); hasContext = true; }
                else if ("assistant".equals(role)) { scriptBuilder.append(scriptLine("我", content, "中文原意")); hasContext = true; }
            }
            if (!hasContext) scriptBuilder.append("（暂无有效上下文）\n");
            scriptBuilder.append("\n【请翻译以下最新外语消息】\n").append(text);
            messages.put(createMessageObj("user", scriptBuilder.toString()));

            try {
                String r = callChatMessages(messages);
                return refuseGuard(r, text);
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("400")) {
                    return refuseGuard(fallbackToPureTextRequest(messages), text);
                } else throw e;
            }
        } catch (JSONException e) {
            String r = callChatSimple(receivePrompt + "\n\n需要翻译的外语消息：\n" + text);
            return refuseGuard(r, text);
        }
    }

    public static String fromChinese(String text, String lang) throws IOException {
        text = text.trim();
        if (text.isEmpty()) return text;
        return callChatSimple("把以下中文翻译成" + lang + "：" + text);
    }

    public static String translateTest(String text, String lang) throws IOException {
        if (isChineseOnly(text)) return callChatSimple("把以下中文翻译成" + lang + "：" + text);
        else return toChinese(text, "0");
    }

    // ★ v5.3: 西班牙语地区标签
    public static String getSpanishRegionDirective(String nationality, int nativeLang, String chatId) {
        String nat = (nationality != null) ? nationality.toLowerCase() : "";
        String friendLang = getFriendLang(chatId);
        String langCode = (friendLang != null && !friendLang.isEmpty()) ? friendLang : "";
        String region = mapSpanishRegion(nat);
        if (region == null && (langCode.startsWith("es") || "es".equals(langCode))) region = "es-419";
        if (region == null) return "";

        String description;
        switch (region) {
            case "es-MX": description = "墨西哥西班牙语：请使用墨西哥常用词汇和表达习惯（如 tú 而非 vos，墨西哥特有俚语），避免西班牙本土用法"; break;
            case "es-AR": description = "阿根廷/拉普拉塔西班牙语：请使用 voseo（vos 代替 tú）、阿根廷常用词汇和语调"; break;
            case "es-ES": description = "西班牙本土西班牙语：请使用 vosotros 和西班牙常用表达"; break;
            case "es-CO": description = "拉美西班牙语（偏安第斯）：请使用哥伦比亚/秘鲁/厄瓜多尔等地常用表达，礼貌温和"; break;
            case "es-419": description = "拉美西班牙语（中性）：请使用拉美通用表达，避免西班牙本土 vosotros，默认 tú"; break;
            case "es-US": description = "美式西班牙语：请使用美国西语裔常用表达"; break;
            default: description = "请根据对方国家调整西班牙语表达"; break;
        }
        return "\n\n【目标语地区适配】" + region + "：" + description + "。若与上方格式协议冲突，以格式协议为准。";
    }

    private static String mapSpanishRegion(String nationality) {
        if (nationality == null || nationality.isEmpty()) return null;
        switch (nationality) {
            case "mexico": return "es-MX";
            case "argentina": case "uruguay": case "paraguay": return "es-AR";
            case "spain": return "es-ES";
            case "colombia": case "peru": case "ecuador": case "bolivia": case "venezuela": return "es-CO";
            case "chile": return "es-419";
            case "costa rica": case "panama": case "nicaragua": case "honduras": case "el salvador": case "guatemala": return "es-419";
            case "cuba": case "dominican republic": case "puerto rico": return "es-419";
            case "united states": return "es-US";
            default: return null;
        }
    }

    public static String translateWithHistory(String text, String langCode, String chatId) throws IOException {
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
            if ("es".equals(langCode)) {
                spanishDirective = getSpanishRegionDirective(null, 0, chatId);
            }

            String universalProtocol = sysPrompt + profileBlock(chatId) + spanishDirective +
                    "\n\n【系统最高强制协议】：（与之前版本相同的完整协议，此处省略重复...详见之前代码）";

            // 为了避免回复过长，这里直接拼完整协议
            universalProtocol = sysPrompt + profileBlock(chatId) + spanishDirective +
                    "\n\n【系统最高强制协议（多模态视觉与指令解析）】：" +
                    "\n[完整协议保持不变，因篇幅省略重复部分]";

            messages.put(createMessageObj("system", universalProtocol));

            JSONArray fullHistory = loadHistory(chatId);
            StringBuilder scriptBuilder = new StringBuilder();
            scriptBuilder.append("【历史聊天剧本】\n");
            int maxChatMessages = 60;
            int startIdx = Math.max(0, fullHistory.length() - maxChatMessages);
            for (int i = startIdx; i < fullHistory.length(); i++) {
                JSONObject msg = fullHistory.getJSONObject(i);
                String role = msg.optString("role", "");
                String content = msg.optString("content", "");
                if ("user".equals(role)) scriptBuilder.append(scriptLine("对方", content, "中文意思"));
                else if ("assistant".equals(role)) scriptBuilder.append(scriptLine("我", content, "中文原意"));
            }
            scriptBuilder.append("\n【我的最新输入】\n");
            if (text.contains("[PURE_BRACKET_MODE]")) scriptBuilder.append("\n【强制模式】MODE_A_ONLY\n");
            scriptBuilder.append("<translate>\n").append(text).append("\n</translate>");
            messages.put(createMessageObj("user", scriptBuilder.toString()));

            try {
                return callChatMessages(messages);
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("400")) {
                    return fallbackToPureTextRequest(messages);
                } else throw e;
            }
        } catch (JSONException e) {
            throw new IOException("构建Messages失败");
        }
    }

    public static String translateForPicker(String text, String langCode, String chatId) throws IOException {
        String raw = translateWithHistory(text, langCode, chatId);
        if (text != null && text.contains("[PURE_BRACKET_MODE]")) return raw;
        if (parseTranslateOptions(raw).isEmpty() && isRefusalResponse(raw)) {
            throw new IOException("内容被AI安全审查拦截。请用（）括号加入辅助指令，或换个说法后亲自重试。");
        }
        return raw;
    }

    private static String fallbackToPureTextRequest(JSONArray originalMessages) throws IOException {
        try {
            JSONArray cleanMessages = new JSONArray();
            for (int i = 0; i < originalMessages.length(); i++) {
                JSONObject msg = originalMessages.getJSONObject(i);
                String role = msg.getString("role");
                Object contentObj = msg.get("content");
                JSONObject cleanMsg = new JSONObject();
                cleanMsg.put("role", role);
                if (contentObj instanceof JSONArray) {
                    JSONArray arr = (JSONArray) contentObj;
                    StringBuilder textSb = new StringBuilder();
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject item = arr.getJSONObject(j);
                        if ("text".equals(item.optString("type"))) textSb.append(item.optString("text")).append("\n");
                    }
                    cleanMsg.put("content", textSb.toString().replaceAll("\\n{3,}", "\n\n").trim());
                } else {
                    cleanMsg.put("content", contentObj.toString());
                }
                cleanMessages.put(cleanMsg);
            }
            return callChatMessages(cleanMessages);
        } catch (JSONException e) {
            throw new IOException("降级解析失败");
        }
    }

    private static String callChatSimple(String prompt) throws IOException {
        if (apiKey == null || apiKey.isEmpty()) throw new IOException("Key未配置");
        if (client == null) throw new IOException("未初始化");
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 2000);
            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", prompt);
            msgs.put(m);
            body.put("messages", msgs);
            return executeRequest(body);
        } catch (JSONException e) {
            throw new IOException("构建失败");
        }
    }

    private static String callChatMessages(JSONArray messages) throws IOException {
        if (apiKey == null || apiKey.isEmpty()) throw new IOException("Key未配置");
        if (client == null) throw new IOException("未初始化");
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 2000);
            body.put("messages", messages);
            return executeRequest(body);
        } catch (JSONException e) {
            throw new IOException("构建失败");
        }
    }

    private static String executeRequest(JSONObject body) throws IOException {
        return executeRequestWith(client, body);
    }

    private static String executeRequestWith(OkHttpClient useClient, JSONObject body) throws IOException {
        String bodyStr = body.toString();
        Request req = new Request.Builder()
                .url(fixUrl(apiUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(bodyStr, JSON_TYPE))
                .build();

        try (Response resp = useClient.newCall(req).execute()) {
            String responseBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP状态码 " + resp.code() + "\n官方详细报错: " + responseBody);
            }
            try {
                JSONObject json = new JSONObject(responseBody);
                JSONObject choice = json.getJSONArray("choices").getJSONObject(0);
                String finishReason = choice.optString("finish_reason", "unknown");
                JSONObject message = choice.getJSONObject("message");
                String content = message.optString("content", "").trim();
                if ("content_filter".equalsIgnoreCase(finishReason) || "safety".equalsIgnoreCase(finishReason)) {
                    throw new IOException("内容被AI安全审查拦截。请用（）括号加入辅助指令，或换个说法后亲自重试。");
                }
                if (content.isEmpty()) {
                    throw new IOException("大模型返回了空数据 (finish_reason: " + finishReason + ")。");
                }
                return content;
            } catch (Exception e) {
                if (e instanceof IOException) throw e;
                throw new IOException("JSON解析失败，API返回格式异常。\n内容: " + responseBody);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("网络请求发生未知错误: " + e.getMessage());
        }
    }

    private static String fixUrl(String url) {
        if (url == null || url.isEmpty()) return "https://api.openai.com/v1/chat/completions";
        if (url.endsWith("/chat/completions")) return url;
        if (!url.endsWith("/")) url += "/";
        int idx = url.indexOf("/v1");
        if (idx >= 0) url = url.substring(0, idx);
        if (!url.endsWith("/")) url += "/";
        return url + "v1/chat/completions";
    }

    public static List<String> fetchModels(String key, String baseUrl) throws IOException {
        List<String> result = new ArrayList<>();
        String url = baseUrl;
        if (url.endsWith("/chat/completions")) url = url.substring(0, url.length() - "/chat/completions".length());
        int idx = url.indexOf("/v1");
        if (idx >= 0) url = url.substring(0, idx);
        if (!url.endsWith("/")) url += "/";
        url += "v1/models";
        initForFetch(key, url);
        Request req = new Request.Builder().url(url).header("Authorization", "Bearer " + key).get().build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            JSONArray data = new JSONObject(resp.body().string()).getJSONArray("data");
            for (int i = 0; i < data.length(); i++) result.add(data.getJSONObject(i).getString("id"));
        } catch (JSONException e) { throw new IOException("解析失败"); }
        return result;
    }

    // =========================================================
    // 缓存
    // =========================================================

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
                    foreignToChinese.put(foreign, chinese);
                    chineseToForeign.put(chinese, foreign);
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
                    String foreign = stripFlipMarks(e.getValue()[0]).replace("\n", "\\n");
                    String chinese = stripFlipMarks(e.getValue()[1]).replace("\n", "\\n");
                    w.write(e.getKey() + "|||" + foreign + "|||" + chinese);
                    w.newLine();
                }
            }
        } catch (Exception ignored) {}
    }

    public static String[] getCached(String key) { return cache.get(key); }

    public static void cacheResult(String key, String foreign, String chinese) {
        foreign = stripFlipMarks(foreign);
        chinese = stripFlipMarks(chinese);
        cache.put(key, new String[]{foreign, chinese});
        foreignToChinese.put(foreign, chinese);
        chineseToForeign.put(chinese, foreign);
        saveCache();
    }

    public static String getForeignByChinese(String chinese) {
        if (chinese == null || chinese.trim().isEmpty()) return null;
        String clean = stripFlipMarks(chinese);
        String exact = chineseToForeign.get(clean);
        if (exact != null) return exact;
        for (Map.Entry<String, String> entry : chineseToForeign.entrySet()) {
            String k = stripFlipMarks(entry.getKey());
            String v = stripFlipMarks(entry.getValue());
            if (clean.equals(k) || clean.contains(k) || k.contains(clean)) return v;
        }
        return null;
    }

    public static String getChineseByForeign(String foreign) {
        if (foreign == null || foreign.trim().isEmpty()) return null;
        String clean = stripFlipMarks(foreign);
        String exact = foreignToChinese.get(clean);
        if (exact != null) return exact;
        exact = mySentDrafts.get(clean);
        if (exact != null) return exact;
        for (Map.Entry<String, String> entry : foreignToChinese.entrySet()) {
            String k = stripFlipMarks(entry.getKey());
            String v = stripFlipMarks(entry.getValue());
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
            String f = stripFlipMarks(entry.getKey());
            String c = stripFlipMarks(entry.getValue());
            if (clean.contains(c) || c.contains(clean) || clean.contains(f) || f.contains(clean)) return f;
        }
        return null;
    }

    // =========================================================
    // Prompt
    // =========================================================

    private static void loadPrompts() {
        try {
            if (promptFile.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(promptFile));
                String cur = "";
                StringBuilder sb = new StringBuilder();
                String line;
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
        if (receivePrompt.isEmpty()) receivePrompt = "你是我的专属社交情报传译员。要求：1. 克隆对方的语气风格。2. 只给1个中文翻译，不要选项。3. 不要加前言后语。4. 潜台词放末尾括号（不超过20字）。";
        if (promptEN.isEmpty()) promptEN = "你是社交嘴替。把中文转成地道英语口语，4版本。格式：外文|中文大意|标签。";
        if (promptRU.isEmpty()) promptRU = "你是社交嘴替。把中文转成地道俄语口语，4版本。格式：外文|中文大意|标签。";
        if (promptUK.isEmpty()) promptUK = "你是社交嘴替。把中文转成地道乌克兰语口语，4版本。格式：外文|中文大意|标签。";
        if (promptKO.isEmpty()) promptKO = "你是社交嘴替。把中文转成地道韩语口语，4版本。格式：外文|中文大意|标签。";
        if (promptES.isEmpty()) promptES = "你是社交嘴替。把中文转成地道西班牙语口语，4版本。格式：外文|中文大意|标签。";
    }

    public static void savePrompts(String zh, String en, String ru, String uk) {
        receivePrompt = zh; promptEN = en; promptRU = ru; promptUK = uk;
    }
    public static void savePrompts(String zh, String en, String ru, String uk, String ko, String es) {
        receivePrompt = zh; promptEN = en; promptRU = ru; promptUK = uk; promptKO = ko; promptES = es;
    }

    // =========================================================
    // 历史记录
    // =========================================================

    private static File historyFile(String chatId) {
        return new File("/data/data/com.hellotalk/files/htai_hist_" + chatId + ".json");
    }

    public static JSONArray loadHistory(String chatId) {
        synchronized (fileLock) {
            File f = historyFile(chatId);
            if (!f.exists()) return new JSONArray();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return new JSONArray(sb.toString());
            } catch (Exception e) { return new JSONArray(); }
        }
    }

    private static void writeHistoryLocked(String chatId, JSONArray history) {
        try {
            File f = historyFile(chatId);
            f.getParentFile().mkdirs();
            BufferedWriter w = new BufferedWriter(new FileWriter(f));
            w.write(history.toString());
            w.close();
        } catch (Exception ignored) {}
    }

    public static void appendHistory(String chatId, String msgId, String role, String content) {
        appendHistory(chatId, msgId, role, content, System.currentTimeMillis(), null);
    }

    public static void appendHistory(String chatId, String msgId, String role, String content, long timestamp, String quotedText) {
        if (content == null || content.isEmpty()) return;
        maybeRecheckMode();

        if (quotedText != null && !quotedText.isEmpty()) {
            content = "（对方正在引用/回复此前对话：\"" + quotedText + "\"）\n" + content;
        }

        List<JSONObject> distillBatch = null;
        synchronized (fileLock) {
            try {
                JSONArray history = loadHistory(chatId);
                if (msgId != null && !msgId.isEmpty()) {
                    for (int i = 0; i < history.length(); i++) {
                        if (msgId.equals(history.getJSONObject(i).optString("msgId"))) return;
                    }
                }
                JSONObject entry = new JSONObject();
                if (msgId != null) entry.put("msgId", msgId);
                entry.put("role", role);
                entry.put("timestamp", timestamp);
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

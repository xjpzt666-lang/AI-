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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.AndroidAppHelper;
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
    public static final Map<String, String> mySentDrafts = new ConcurrentHashMap<>();

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

    private static final Pattern PAREN_TAIL = Pattern.compile("[（(]([^()（）]*)[)）]\\s*$");
    private static final Pattern NUMBER_PREFIX = Pattern.compile(
            "^(?:版本\\s*\\d*|[Oo]ption\\s*\\d*|选项\\s*\\d*|\\d{1,2}\\s*[.、)）:：]|[一二三四五六①-⑳]+\\s*[.、)）:：]?)\\s*");

    private static final int MAX_TOTAL_BASE64_CHARS = 900_000;

    // =========================================================
    // ★ 记忆系统 2.0：档案 + 蒸馏 + 备份仓库 + 主号/一次性
    // =========================================================

    /** 保险箱目录（在 HelloTalk 数据目录之外，清数据不会波及） */
    private static final String STORE_DIR = "/data/local/tmp/htai_store";
    /** 模式标记文件：main=主账号 / temp=一次性 / pending=待认领 */
    private static final String MARKER_FILE = "/data/local/tmp/htai_mem_mode.txt";

    private static volatile String memMode = "main";
    private static volatile boolean memPending = false;
    private static volatile boolean pendingToastShown = false;
    private static volatile long lastModeRecheckTs = 0;
    private static volatile long lastBackupTs = 0;
    private static volatile long lastDistillFailTs = 0;

    /** 历史保留目标条数（蒸馏后回到这个规模） */
    private static final int HISTORY_SOFT_CAP = 100;
    /** 攒够这么多条旧消息才蒸馏一次 */
    private static final int DISTILL_BATCH_MIN = 30;
    /** 蒸馏连续失败时的强制裁剪红线，防文件无限膨胀 */
    private static final int HISTORY_HARD_CAP = 180;
    /** 蒸馏失败后的冷却时间 */
    private static final long DISTILL_COOLDOWN_MS = 5 * 60_000;
    /** 档案硬上限（字符） */
    private static final int PROFILE_HARD_CAP = 800;
    /** 备份间隔 */
    private static final long BACKUP_INTERVAL_MS = 3 * 60_000;
    /** 待认领状态下重读标记文件的间隔 */
    private static final long MODE_RECHECK_MS = 60_000;

    private static volatile OkHttpClient distillClient = null;

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

        // ★ 最后初始化记忆模式（主账号/一次性/待认领）
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
    // ★ 记忆模式：检测 / 认领复核 / 备份
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

            // 上次已判定待认领、还没去遥控器选择
            if ("pending".equals(marker)) {
                memPending = true;
                toastPending();
                Log.w(TAG, "记忆模式：待认领（等待遥控器选择）");
                return;
            }

            // 正常状态：沙箱里有记忆文件
            if (sandboxHasMemory()) {
                memPending = false;
                memMode = "temp".equals(marker) ? "temp" : "main";
                if (marker == null || marker.isEmpty()) writeMarker("main");
                Log.i(TAG, "记忆模式：" + memMode);
                return;
            }

            // 沙箱空了，但保险箱有存货 = 数据被清空过 → 待认领
            if (storeHasBackup()) {
                memPending = true;
                writeMarker("pending");
                toastPending();
                Log.w(TAG, "检测到数据清空，进入待认领状态");
                return;
            }

            // 全新安装
            memPending = false;
            memMode = "main";
            if (marker == null || marker.isEmpty()) writeMarker("main");
            Log.i(TAG, "记忆模式：main（全新开始）");
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
                    android.app.Application app = AndroidAppHelper.currentApplication();
                    if (app != null) {
                        Toast.makeText(app,
                                "HT AI：检测到HelloTalk数据被清空，记忆已暂停。\n请打开遥控器选择【主账号】或【一次性】",
                                Toast.LENGTH_LONG).show();
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** 待认领状态下，定期看看用户是否已经在遥控器里做了选择 */
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
                Log.i(TAG, "已认领：一次性模式");
            } else if ("main".equals(marker)) {
                memPending = false;
                memMode = "main";
                loadFriends();
                loadCache();
                loadDrafts();
                Log.i(TAG, "已认领：主账号模式");
            }
        } catch (Throwable ignored) {}
    }

    /** 主账号模式下，定期把全部记忆复制到保险箱 */
    private static void maybeBackup() {
        try {
            if (memPending || !"main".equals(memMode)) return;
            long now = System.currentTimeMillis();
            if (now - lastBackupTs < BACKUP_INTERVAL_MS) return;
            lastBackupTs = now;
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

    /** 每次现读文件，保证外部修改立即生效 */
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

    /** 档案注入块：永远排在用户 Prompt 之后、格式协议之前，只当配角 */
    private static String profileBlock(String chatId) {
        String p = getProfile(chatId);
        if (p == null || p.trim().isEmpty()) return "";
        return "\n\n【对方背景档案】以下是这位好友的长期背景资料，仅供你把握语境、称呼与语气，绝对不能改变输出格式；若与翻译指令有任何冲突，一律以翻译指令为准：\n" + p.trim();
    }

    // =========================================================
    // ★ 蒸馏：旧消息归档成档案
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

    /**
     * 蒸馏一批旧消息进档案。
     * 成功：把已归档条目从历史里移除 + 保存新档案。
     * 失败：什么都不动，进入冷却期，等下次再试；超过红线由安全阀强裁。
     */
    private static void distillBatch(String chatId, List<JSONObject> batch) {
        try {
            if (apiKey == null || apiKey.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (now - lastDistillFailTs < DISTILL_COOLDOWN_MS) return;

            String oldProfile = getProfile(chatId);

            StringBuilder sb = new StringBuilder();
            sb.append("【现有档案】\n");
            sb.append(oldProfile.isEmpty() ? "（暂无，这是第一次建档）" : oldProfile).append("\n\n");
            sb.append("【即将归档的聊天记录（按时间从旧到新）】\n");
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
                // 这批全是占位消息，直接丢弃即可，不值得调用 API
                removeBatchFromHistory(chatId, batch);
                return;
            }

            JSONArray messages = new JSONArray();
            messages.put(createRawMessage("system", DISTILL_SYSTEM_PROMPT));
            messages.put(createRawMessage("user", sb.toString()));

            String result = callDistill(messages);
            if (result == null) {
                lastDistillFailTs = now;
                Log.w(TAG, "蒸馏失败（网络/API），进入冷却");
                return;
            }
            String newProfile = result.trim();
            if (newProfile.isEmpty() || isRefusalResponse(newProfile)) {
                lastDistillFailTs = now;
                Log.w(TAG, "蒸馏返回异常内容，进入冷却");
                return;
            }
            if (newProfile.length() > PROFILE_HARD_CAP) {
                newProfile = newProfile.substring(0, PROFILE_HARD_CAP);
            }

            removeBatchFromHistory(chatId, batch);
            writeProfileFile(chatId, newProfile);
            lastDistillFailTs = 0;
            Log.i(TAG, "蒸馏完成，档案更新：" + newProfile.length() + " 字");

            // 档案刚更新，立即备份一次
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

    // =========================================================
    // ★ v82.4：剧本双语注记
    // =========================================================

    private static String scriptLine(String who, String content, String noteLabel) {
        try {
            String clean = stripFlipMarks(content);
            String zh = (clean == null) ? null : foreignToChinese.get(clean);
            if (zh != null && !zh.isEmpty() && !zh.equals(clean)) {
                return who + ": " + content + "（" + noteLabel + "：" + zh + "）\n";
            }
        } catch (Throwable ignored) {}
        return who + ": " + content + "\n";
    }

    // =========================================================
    // ★ 拒绝话术识别
    // =========================================================

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
    // ★ 我的中文原文草稿：持久化（翻转按钮的数据来源）
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
                Log.i(TAG, "已恢复本地草稿映射: " + mySentDrafts.size() + " 条");
            }
        } catch (Exception ignored) {}
    }

    private static void saveDrafts() {
        try {
            if (draftsFile == null) return;
            if (mySentDrafts.size() > 1200) {
                Iterator<String> it = mySentDrafts.keySet().iterator();
                int removeCount = mySentDrafts.size() - 900;
                while (it.hasNext() && removeCount > 0) {
                    String k = it.next();
                    it.remove();
                    foreignToChinese.remove(k);
                    removeCount--;
                }
            }
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
            saveDrafts();
        } catch (Exception ignored) {}
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
    // 图片 Base64
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
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

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
                if (bytes.length <= 90 * 1024) {
                    break;
                }
            }

            bitmap.recycle();
            if (bestBytes == null || bestBytes.length == 0) return null;

            String result = Base64.encodeToString(bestBytes, Base64.NO_WRAP);
            imageBase64Cache.put(cacheKey, result);
            return result;
        } catch (Throwable e) {
            Log.e(TAG, "图片转Base64失败: " + e.getMessage());
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
        if (content == null) {
            result.cleanText = "";
            return result;
        }

        String working = content;

        Matcher pureMatcher = PURE_BRACKET_MODE_PATTERN.matcher(working);
        if (pureMatcher.find()) {
            result.pureBracketMode = true;
            working = pureMatcher.replaceAll("").trim();
        }

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
            clean = clean + "\n[系统提示：当前回复目标是一张图片，但本地文件路径未获取到，请不要把背景图误认成目标图，只能结合上下文保守回答。]";
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
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
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
                        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
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
        return s.replaceAll("([ ]?[🌐🔄]+)$", "").trim();
    }

    // =========================================================
    // ★ 翻译结果清洗：干掉破折号、分号
    // =========================================================

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

    // =========================================================
    // ★ 选项解析：管道/表格/序号/括号 全兼容，最多取4条
    // =========================================================

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
                for (String p : parts) {
                    String c2 = p.trim();
                    if (!c2.isEmpty()) cells.add(c2);
                }
                if (cells.isEmpty()) continue;
                foreign = cells.get(0);
                if (cells.size() > 1) chinese = cells.get(1);
                if (cells.size() > 2) label = cells.get(2);
            } else {
                String core = NUMBER_PREFIX.matcher(line).replaceFirst("").trim();
                Matcher m = PAREN_TAIL.matcher(core);
                String paren = "";
                if (m.find()) {
                    paren = m.group(1).trim();
                    core = core.substring(0, m.start()).trim();
                }
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
            foreign = foreign.replaceAll("^[\"'“”‘’「 ]+|[\"'“”‘’」 ]+$", "").trim();
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
        if (splitData.length >= 2) {
            return splitData[0].trim().replace("*", "");
        }
        String[] lines = result.split("\n");
        int firstOptionLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim().replace("*", "");
            if (t.isEmpty()) continue;
            if (t.contains("|") || NUMBER_PREFIX.matcher(t).find()) {
                firstOptionLine = i;
                break;
            }
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
                    "\n\n【系统隐性协议（多模态）】：" +
                    "\n1. 你可能会同时看到文本和图片。" +
                    "\n2. 如果消息中带有[背景上下文图片]，那是最近聊天背景，用于帮助理解上下文。" +
                    "\n3. 如果消息中带有[当前回复目标图]，那是当前重点图，优先关注这张。" +
                    "\n4. 你只需要把最后一条外语消息翻译成中文，必要时结合图片消歧。" +
                    "\n5. 不要描述你收到了图片，也不要解释协议。" +
                    "\n6. 【语境对齐】翻译前先在心里判断：对方这句话是在回应剧本里我的哪一句话、或延续哪个话题。" +
                    "翻译时必须与该语境连贯（代词、指代、称呼、时态都要对得上），" +
                    "但绝对不要输出任何分析过程，只给最终的1个中文翻译。";

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
                if ("user".equals(role)) {
                    scriptBuilder.append(scriptLine("对方", content, "中文意思"));
                    hasContext = true;
                } else if ("assistant".equals(role)) {
                    scriptBuilder.append(scriptLine("我", content, "中文原意"));
                    hasContext = true;
                }
            }

            if (!hasContext) scriptBuilder.append("（暂无有效上下文）\n");
            scriptBuilder.append("\n【请翻译以下最新外语消息】\n").append(text);

            messages.put(createMessageObj("user", scriptBuilder.toString()));

            try {
                String r = callChatMessages(messages);
                return refuseGuard(r, text);
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("400")) {
                    String r = fallbackToPureTextRequest(messages);
                    return refuseGuard(r, text);
                } else {
                    throw e;
                }
            }
        } catch (JSONException e) {
            String r = callChatSimple(receivePrompt + "\n\n需要翻译的外语消息：\n" + text);
            return refuseGuard(r, text);
        }
    }

    public static String fromChinese(String text, String lang) throws IOException {
        text = text.trim();
        if (text.isEmpty()) return text;
        String prompt = "把以下中文翻译成" + lang + "：" + text;
        return callChatSimple(prompt);
    }

    public static String translateTest(String text, String lang) throws IOException {
        if (isChineseOnly(text)) {
            return callChatSimple("把以下中文翻译成" + lang + "：" + text);
        } else {
            return toChinese(text, "0");
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

            String universalProtocol = sysPrompt + profileBlock(chatId) +
                    "\n\n【系统最高强制协议（多模态视觉与指令解析）】：" +
                    "\n1. 下方是【历史聊天剧本】（带中文注记）。如果消息里附带了图片，你已经可以看到它们。" +
                    "\n2. [背景上下文图片] = 最近聊天背景，仅用于帮助理解上下文。" +
                    "\n3. [当前回复目标图] = 我此刻正在回复的焦点图，优先分析这张。" +
                    "\n4. 如果提示中出现【当前回复目标是一张图片，但本地文件路径未获取到】，说明你不能把背景图误认为焦点图，必须保守回答。" +
                    "\n5. 剧本后，<translate> 标签内包裹的是我刚刚输入的【最新文字】。请严格判断格式，执行以下两种模式之一：" +
                    "\n6. 【绝对服从】：如果用户消息中出现【强制模式】MODE_A_ONLY，你必须无条件执行【模式A】，严禁出现任何翻译选项！" +
                    "\n7. 【绝对死刑标点黑名单】：在任何翻译结果中，绝对禁止使用破折号(—)、半角分号(;)、全角分号(；)。只能使用逗号(,)、句号(.)、问号(?)、感叹号(!)和省略号(...)。违反即整条作废！" +
                    "\n8. 【回复目标推理（必须执行）】剧本里可能有多条对方消息，而我可能没有使用引用功能。翻译前必须先判断：" +
                    "\n  ① 我的最新输入是在回复对方的哪一条消息（或哪个话题）？判断出来后，在上半部分写一行：判断：你在回复对方的\"xxx\"；" +
                    "\n  ② 或者，我的输入是不是在续写/补充我自己上一条没说完的话？如果是，写一行：判断：你在续写自己的上一条话，并且译文必须与我上一条外语消息自然衔接（时态、代词、逻辑连贯，像同一句话说完）；" +
                    "\n  ③ 实在无法判断时，写一行：判断：未定位到具体回复目标，按通用语气翻译，然后正常翻译。" +
                    "\n  翻译时必须贴合所判断的语境：称呼、语气、用词习惯都要与那段对话一致，严禁脱离上下文孤立翻译。" +

                    "\n\n【模式A：纯对话求助模式（不翻译）】" +
                    "\n► 触发条件：文字全部被括号包裹，或明确包含 MODE_A_ONLY。" +
                    "\n► 任务：结合焦点图优先、背景图次之，直接客观回答我的问题。" +
                    "\n► 严禁：严禁输出4个翻译选项！严禁把括号问题翻译成外语！" +
                    "\n► 输出格式约束：上半部分写详细解答，然后换行输入 `==========`（十个等号），在此下方随便给一个占位符。" +

                    "\n\n【模式B：标准翻译 + 附加指令/提问模式】" +
                    "\n► 触发条件：存在正常中文正文，且不是模式A。" +
                    "\n► 任务：先执行第8条【回复目标推理】，把判断结果写在上半部分；然后严格结合该语境，把括号外正文翻译成地道语言（避开黑名单词汇，绝对不使用破折号、分号）。" +
                    "\n► 【输出排版绝对红线】（必须严格分成上下两段，中间用 `==========` 分割，这是维持系统不崩溃的底线）：" +
                    "\n\n【上半部分：分析与解答区】" +
                    "\n（第一行必须先写第8条要求的\"判断：……\"，之后如果你想做任何语境分析、或者回答括号内的提问，请尽情写，但必须全部放在上半部分！）" +
                    "\n\n==========" +
                    "\n\n【下半部分：严格的选项区】" +
                    "\n（在此分隔线下方，绝对、永远、严禁写任何废话说明！必须且只能输出【绝对恰好 4 行】翻译版本，不可多一行也不可少一行！如果你只输出3行或2行，系统将直接判定为严重错误并拒绝接收！）" +
                    "\n每行的格式必须严格为：外语|中文大意|语气标签" +
                    "\n注意：绝对不准加数字序号（如 1. 2.），绝不准用Markdown表格，必须且只能用 `|` 分割！" +
                    "\n最后再重复一遍：下半部分必须恰好4行！少一行系统就会崩溃报错！";

            messages.put(createMessageObj("system", universalProtocol));

            JSONArray fullHistory = loadHistory(chatId);
            StringBuilder scriptBuilder = new StringBuilder();
            scriptBuilder.append("【历史聊天剧本】\n");

            // ★ 上下文保持 60 条，不做改动
            int maxChatMessages = 60;
            int startIdx = Math.max(0, fullHistory.length() - maxChatMessages);

            for (int i = startIdx; i < fullHistory.length(); i++) {
                JSONObject msg = fullHistory.getJSONObject(i);
                String role = msg.optString("role", "");
                String content = msg.optString("content", "");

                if ("user".equals(role)) {
                    scriptBuilder.append(scriptLine("对方", content, "中文意思"));
                } else if ("assistant".equals(role)) {
                    scriptBuilder.append(scriptLine("我", content, "中文原意"));
                }
            }

            scriptBuilder.append("\n【我的最新输入】\n");

            boolean forceModeA = text.contains("[PURE_BRACKET_MODE]");
            if (forceModeA) {
                scriptBuilder.append("\n【强制模式】MODE_A_ONLY\n");
            }
            scriptBuilder.append("<translate>\n").append(text).append("\n</translate>");

            messages.put(createMessageObj("user", scriptBuilder.toString()));

            try {
                return callChatMessages(messages);
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

    /**
     * ★ 弹窗专用翻译入口（v82.3 规则不变）：
     * 按「译」= 只调用一次 API，无论什么结果都绝不自动重试。
     */
    public static String translateForPicker(String text, String langCode, String chatId) throws IOException {
        String raw = translateWithHistory(text, langCode, chatId);

        if (text != null && text.contains("[PURE_BRACKET_MODE]")) {
            return raw;
        }

        if (parseTranslateOptions(raw).isEmpty() && isRefusalResponse(raw)) {
            Log.w(TAG, "检测到AI安全审查拒绝话术，直接报错，不自动重试");
            throw new IOException("内容被AI安全审查拦截（可能是你的输入或引用的对方原话含敏感词）。请用（）括号加入辅助指令，或换个说法后亲自重试。");
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
                        if ("text".equals(item.optString("type"))) {
                            textSb.append(item.optString("text")).append("\n");
                        }
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
        Log.i(TAG, "request body chars = " + bodyStr.length());

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
                    throw new IOException("内容被AI安全审查拦截（可能涉及敏感词汇）。请用（）括号加入辅助指令，或换个说法后亲自重试。");
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
        if (url.endsWith("/chat/completions")) {
            url = url.substring(0, url.length() - "/chat/completions".length());
        }
        int idx = url.indexOf("/v1");
        if (idx >= 0) url = url.substring(0, idx);
        if (!url.endsWith("/")) url += "/";
        url += "v1/models";

        initForFetch(key, url);
        Request req = new Request.Builder().url(url).header("Authorization", "Bearer " + key).get().build();

        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            JSONArray data = new JSONObject(resp.body().string()).getJSONArray("data");
            for (int i = 0; i < data.length(); i++) {
                result.add(data.getJSONObject(i).getString("id"));
            }
        } catch (JSONException e) {
            throw new IOException("解析失败");
        }

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

    public static String getDraftFuzzy(String sentForeignText) {
        if (sentForeignText == null || sentForeignText.trim().isEmpty()) return null;
        String clean = stripFlipMarks(sentForeignText);
        if (mySentDrafts.containsKey(clean)) return mySentDrafts.get(clean);
        for (Map.Entry<String, String> entry : mySentDrafts.entrySet()) {
            String key = stripFlipMarks(entry.getKey());
            if (key == null || key.isEmpty()) continue;
            if (clean.contains(key) || key.contains(clean)) return entry.getValue();
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
    // 历史记录（★ 记忆系统 2.0 重写版）
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

    /** 必须在持有 fileLock 时调用 */
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

        List<JSONObject> distillBatch = null;

        synchronized (fileLock) {
            try {
                JSONArray history = loadHistory(chatId);
                if (msgId != null && !msgId.isEmpty()) {
                    for (int i = 0; i < history.length(); i++) {
                        JSONObject obj = history.getJSONObject(i);
                        if (msgId.equals(obj.optString("msgId"))) return;
                    }
                }
                if (quotedText != null && !quotedText.isEmpty()) {
                    content = "（针对我的原话：\"" + quotedText + "\" 进行了回复）\n" + content;
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
                    // ★ 安全阀：蒸馏长时间失败，强制裁剪防膨胀（极端情况才走到）
                    JSONArray trimmed = new JSONArray();
                    for (int i = history.length() - HISTORY_SOFT_CAP; i < history.length(); i++) trimmed.put(history.get(i));
                    writeHistoryLocked(chatId, trimmed);
                    Log.w(TAG, "蒸馏长期失败，触发安全阀强制裁剪: " + chatId);
                } else if (history.length() >= HISTORY_SOFT_CAP + DISTILL_BATCH_MIN) {
                    // ★ 攒够一批旧消息：先整份落盘，随后在锁外蒸馏归档
                    int batchCount = history.length() - HISTORY_SOFT_CAP;
                    distillBatch = new ArrayList<>();
                    for (int i = 0; i < batchCount; i++) distillBatch.add(history.getJSONObject(i));
                    writeHistoryLocked(chatId, history);
                } else {
                    writeHistoryLocked(chatId, history);
                }
            } catch (Exception ignored) {}
        }

        if (distillBatch != null && !distillBatch.isEmpty()) {
            distillBatch(chatId, distillBatch);
        }

        maybeBackup();
    }
}

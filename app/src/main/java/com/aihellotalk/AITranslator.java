package com.aihellotalk;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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

    public static final Map<String, String> cache = new ConcurrentHashMap<>();
    private static File cacheFile;
    private static File promptFile;

    public static String receivePrompt = "";
    public static String promptEN = "";
    public static String promptRU = "";
    public static String promptUK = "";
    // ★ 新增：韩语和西班牙语 Prompt
    public static String promptKO = "";
    public static String promptES = "";

    private static File friendsFile = new File("/data/data/com.hellotalk/files/htai_friends.json");
    private static JSONObject friendsData = new JSONObject();

    // 只匹配平假名、片假名、半角片假名、长音符号
    // 不再包含 CJK 统一汉字，避免中文被误判为日语
    private static final Pattern JAPANESE_PATTERN = Pattern.compile(
            "[\\u3040-\\u30FF\\uFF65-\\uFF9F\\u30FC]+"
    );

    // ──────────────────────────────────────
    // 初始化
    // ──────────────────────────────────────

    public static void init(String key, String url, String m) {
        apiKey = key;
        apiUrl = url;
        model = m;

        Log.i(TAG, "初始化: Key长度=" + (key != null ? key.length() : 0) + " url=" + url + " model=" + m);

        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

        cacheFile = new File("/data/data/com.hellotalk/files/htai_cache.txt");
        promptFile = new File("/data/local/tmp/htai_prompts.txt");

        loadCache();
        loadFriends();
        loadPrompts();

        Log.i(TAG, "缓存:" + cache.size() + "条, 朋友:" + friendsData.length() + "位");
    }

    public static void initForFetch(String key, String url) {
        apiKey = key;
        apiUrl = url;

        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build();
    }

    // ──────────────────────────────────────
    // 朋友管理
    // ──────────────────────────────────────

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
            if (friendsData.has(chatId)) {
                friend = friendsData.getJSONObject(chatId);
            }
            if (name != null && !name.isEmpty()) {
                friend.put("name", name);
            } else if (!friend.has("name")) {
                friend.put("name", chatId);
            }
            friend.put("lang", langCode != null ? langCode : "en");
            friend.put("lastTime", System.currentTimeMillis());
            friendsData.put(chatId, friend);
            saveFriends();
            Log.i(TAG, "注册朋友: " + friend.optString("name") + " (" + langCode + ")");
        } catch (JSONException ignored) {}
    }

    public static String getFriendLang(String chatId) {
        try {
            if (friendsData.has(chatId)) {
                return friendsData.getJSONObject(chatId).optString("lang", "en");
            }
        } catch (JSONException ignored) {}
        return "en";
    }

    public static String getFriendName(String chatId) {
        try {
            if (friendsData.has(chatId)) {
                return friendsData.getJSONObject(chatId).optString("name", chatId);
            }
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

    // ──────────────────────────────────────
    // 语言/文字检测（修复版）
    // ──────────────────────────────────────

    /**
     * 是否包含日语（仅平假名/片假名/半角片假名/长音符号）
     * 不会把中文误判为日语
     */
    public static boolean containsJapanese(String s) {
        if (s == null || s.isEmpty()) return false;
        return JAPANESE_PATTERN.matcher(s).find();
    }

    /**
     * 是否纯中文（包含CJK汉字，但不包含日语假名）
     */
    public static boolean isChineseOnly(String s) {
        if (s == null || s.isEmpty()) return false;
        // 如果包含日语假名，不算纯中文
        if (containsJapanese(s)) return false;
        // 检查是否包含中日韩统一汉字
        for (char c : s.toCharArray()) {
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
        if (containsJapanese(text)) {
            Log.i(TAG, "跳过翻译(日语): " + text.substring(0, Math.min(20, text.length())));
            return false;
        }
        if (isChineseOnly(text)) {
            return false;
        }
        return true;
    }

    // ──────────────────────────────────────
    // 翻译
    // ──────────────────────────────────────

    public static String toChinese(String text) throws IOException {
        text = text.trim();
        if (text.isEmpty()) return text;
        if (!needTranslateToChinese(text)) return text;
        String prompt = receivePrompt + "\n\n需要翻译的外语消息：\n" + text;
        return callChatSimple(prompt);
    }

    public static String fromChinese(String text, String lang) throws IOException {
        text = text.trim();
        if (text.isEmpty()) return text;
        String prompt = "把以下中文翻译成" + lang + "：" + text;
        return callChatSimple(prompt);
    }

    public static String translateTest(String text, String lang) throws IOException {
        if (isChineseOnly(text)) {
            String prompt = "把以下中文翻译成" + lang + "：" + text;
            return callChatSimple(prompt);
        } else {
            return toChinese(text);
        }
    }

    public static String translateWithHistory(String text, String langCode, String chatId) throws IOException {
        try {
            JSONArray messages = new JSONArray();

            String sysPrompt;
            switch (langCode) {
                case "ru":
                    sysPrompt = promptRU;
                    break;
                case "uk":
                    sysPrompt = promptUK;
                    break;
                // ★ 新增：韩语和西班牙语
                case "ko":
                    sysPrompt = promptKO;
                    break;
                case "es":
                    sysPrompt = promptES;
                    break;
                default:
                    sysPrompt = promptEN;
                    break;
            }

            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", sysPrompt);
            messages.put(sys);

            JSONArray history = loadHistory(chatId);
            for (int i = 0; i < history.length(); i++) {
                messages.put(history.get(i));
            }

            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", "待翻译中文：" + text);
            messages.put(user);

            return callChatMessages(messages);
        } catch (JSONException e) {
            throw new IOException("构建Messages失败");
        }
    }

    // ──────────────────────────────────────
    // API 调用
    // ──────────────────────────────────────

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
        Request req = new Request.Builder()
                .url(fixUrl(apiUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code());
            }
            String s = resp.body().string();
            try {
                JSONObject json = new JSONObject(s);
                return json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim();
            } catch (JSONException e) {
                throw new IOException("JSON:" + e.getMessage());
            }
        }
    }

    // ──────────────────────────────────────
    // URL 处理
    // ──────────────────────────────────────

    private static String fixUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "https://api.openai.com/v1/chat/completions";
        }
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        if (!url.endsWith("/")) url += "/";
        int idx = url.indexOf("/v1");
        if (idx >= 0) {
            url = url.substring(0, idx);
        }
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
        if (idx >= 0) {
            url = url.substring(0, idx);
        }
        if (!url.endsWith("/")) url += "/";
        url += "v1/models";

        initForFetch(key, url);

        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + key)
                .get()
                .build();

        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            String s = resp.body().string();
            JSONObject json = new JSONObject(s);
            JSONArray data = json.getJSONArray("data");
            for (int i = 0; i < data.length(); i++) {
                result.add(data.getJSONObject(i).getString("id"));
            }
        } catch (JSONException e) {
            throw new IOException("解析模型列表失败");
        }
        return result;
    }

    // ──────────────────────────────────────
    // 缓存
    // ──────────────────────────────────────

    private static void loadCache() {
        if (!cacheFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(cacheFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                int idx = line.indexOf("|||");
                if (idx > 0) {
                    cache.put(line.substring(0, idx), line.substring(idx + 3));
                }
            }
        } catch (Exception ignored) {}
    }

    public static void saveCache() {
        try {
            cacheFile.getParentFile().mkdirs();
            try (BufferedWriter w = new BufferedWriter(new FileWriter(cacheFile))) {
                for (Map.Entry<String, String> e : cache.entrySet()) {
                    w.write(e.getKey() + "|||" + e.getValue());
                    w.newLine();
                }
            }
        } catch (Exception ignored) {}
    }

    public static String getCached(String key) {
        return cache.get(key);
    }

    public static void cacheResult(String key, String value) {
        cache.put(key, value);
        saveCache();
    }

    // ──────────────────────────────────────
    // Prompt 加载（★ 已增加韩语和西班牙语）
    // ──────────────────────────────────────

    private static void loadPrompts() {
        try {
            if (promptFile.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(promptFile));
                String cur = "";
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("###ZH###")) {
                        cur = "ZH";
                        sb.setLength(0);
                    } else if (line.startsWith("###EN###")) {
                        if (cur.equals("ZH")) receivePrompt = sb.toString().trim();
                        cur = "EN";
                        sb.setLength(0);
                    } else if (line.startsWith("###RU###")) {
                        if (cur.equals("EN")) promptEN = sb.toString().trim();
                        cur = "RU";
                        sb.setLength(0);
                    } else if (line.startsWith("###UK###")) {
                        if (cur.equals("RU")) promptRU = sb.toString().trim();
                        cur = "UK";
                        sb.setLength(0);
                    // ★ 新增：韩语分段
                    } else if (line.startsWith("###KO###")) {
                        if (cur.equals("UK")) promptUK = sb.toString().trim();
                        cur = "KO";
                        sb.setLength(0);
                    // ★ 新增：西班牙语分段
                    } else if (line.startsWith("###ES###")) {
                        if (cur.equals("KO")) promptKO = sb.toString().trim();
                        cur = "ES";
                        sb.setLength(0);
                    } else {
                        sb.append(line).append("\n");
                    }
                }
                // 文件末尾的段落
                if (cur.equals("ES")) promptES = sb.toString().trim();
                r.close();
            }
        } catch (Exception ignored) {}

        // 默认值（如果 prompt 文件缺失或段落为空）
        if (receivePrompt.isEmpty())
            receivePrompt = "你是一个社交情报传译员。代入对方身份，将外语翻译成地道有呼吸感的中文。";
        if (promptEN.isEmpty())
            promptEN = "你是社交嘴替。把中文转成地道英语口语，4版本：自然/暖男/奶狗/推荐。格式：外文|中文大意|标签。";
        if (promptRU.isEmpty())
            promptRU = "你是社交嘴替。把中文转成地道俄语口语。4版本。格式：外文|中文大意|标签。";
        if (promptUK.isEmpty())
            promptUK = "你是社交嘴替。把中文转成地道乌克兰语口语。4版本。格式：外文|中文大意|标签。";
        // ★ 新增：韩语和西班牙语默认 Prompt
        if (promptKO.isEmpty())
            promptKO = "你是社交嘴替。把中文转成地道韩语口语。4版本。格式：外文|中文大意|标签。";
        if (promptES.isEmpty())
            promptES = "你是社交嘴替。把中文转成地道西班牙语口语。4版本。格式：外文|中文大意|标签。";
    }

    // ★ 修改：参数从4个改为6个，增加 ko 和 es
    public static void savePrompts(String zh, String en, String ru, String uk, String ko, String es) {
        receivePrompt = zh;
        promptEN = en;
        promptRU = ru;
        promptUK = uk;
        promptKO = ko;
        promptES = es;
    }

    // ──────────────────────────────────────
    // 聊天历史
    // ──────────────────────────────────────

    private static File historyFile(String chatId) {
        return new File("/data/data/com.hellotalk/files/htai_hist_" + chatId + ".json");
    }

    public static JSONArray loadHistory(String chatId) {
        File f = historyFile(chatId);
        if (!f.exists()) return new JSONArray();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return new JSONArray(sb.toString());
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static void appendHistory(String chatId, String role, String content) {
        if (content == null || content.isEmpty()) return;
        try {
            JSONArray history = loadHistory(chatId);
            JSONObject entry = new JSONObject();
            entry.put("role", role);
            String display = content.length() > 200 ? content.substring(0, 200) : content;
            entry.put("content", display);
            history.put(entry);

            File f = historyFile(chatId);
            f.getParentFile().mkdirs();
            try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
                w.write(history.toString());
            }
        } catch (Exception ignored) {}
    }

    public static List<String[]> loadHistoryForDisplay(String chatId) {
        List<String[]> list = new ArrayList<>();
        JSONArray history = loadHistory(chatId);
        for (int i = 0; i < history.length(); i++) {
            try {
                JSONObject obj = history.getJSONObject(i);
                String role = obj.optString("role", "");
                String content = obj.optString("content", "");
                if ("user".equals(role)) {
                    list.add(new String[]{"我", content});
                } else {
                    list.add(new String[]{"AI", content});
                }
            } catch (Exception ignored) {}
        }
        return list;
    }
}

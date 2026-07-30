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
    public static String promptKO = "";
    public static String promptES = "";

    private static File friendsFile = new File("/data/data/com.hellotalk/files/htai_friends.json");
    private static JSONObject friendsData = new JSONObject();

    // ★ 并发锁：解决多线程读写导致 JSON 破损的致命 Bug
    private static final Object fileLock = new Object();

    private static final Pattern JAPANESE_PATTERN = Pattern.compile(
            "[\\u3040-\\u30FF\\uFF65-\\uFF9F\\u30FC]+"
    );

    public static void init(String key, String url, String m) {
        apiKey = key;
        apiUrl = url;
        model = m;

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
    // 朋友管理 (保持不变)
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

    // ──────────────────────────────────────
    // 语言检测 (保持不变)
    // ──────────────────────────────────────
    public static boolean containsJapanese(String s) {
        if (s == null || s.isEmpty()) return false;
        return JAPANESE_PATTERN.matcher(s).find();
    }

    public static boolean isChineseOnly(String s) {
        if (s == null || s.isEmpty()) return false;
        if (containsJapanese(s)) return false;
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

    // ──────────────────────────────────────
    // AI 核心组装逻辑
    // ──────────────────────────────────────

    // ★ 视觉引擎解析器：将字符串转换为标准 OpenAI Vision 格式
    private static JSONObject createMessageObj(String role, String content) throws JSONException {
        JSONObject msgObj = new JSONObject();
        if (content.contains("[IMAGE_BASE64:")) {
            // 绝大多数模型要求发送图片的身份必须是 user
            msgObj.put("role", "user"); 
            int start = content.indexOf("[IMAGE_BASE64:");
            int end = content.indexOf("]", start);
            String b64 = content.substring(start + 14, end);
            String txt = content.substring(0, start) + content.substring(end + 1);
            
            JSONArray arr = new JSONArray();
            JSONObject txtObj = new JSONObject();
            txtObj.put("type", "text");
            txtObj.put("text", txt.trim().isEmpty() ? "请参考这张图片：" : txt.trim());
            arr.put(txtObj);
            
            JSONObject imgObj = new JSONObject();
            imgObj.put("type", "image_url");
            JSONObject urlObj = new JSONObject();
            urlObj.put("url", "data:image/jpeg;base64," + b64);
            imgObj.put("image_url", urlObj);
            arr.put(imgObj);
            
            msgObj.put("content", arr);
        } else {
            msgObj.put("role", role);
            msgObj.put("content", content);
        }
        return msgObj;
    }

    public static String translateWithHistory(String text, String langCode, String chatId) throws IOException {
        try {
            JSONArray messages = new JSONArray();

            String sysPrompt;
            switch (langCode) {
                case "ru": sysPrompt = promptRU; break;
                case "uk": sysPrompt = promptUK; break;
                case "ko": sysPrompt = promptKO; break;
                case "es": sysPrompt = promptES; break;
                default:   sysPrompt = promptEN; break;
            }

            messages.put(createMessageObj("system", sysPrompt));

            JSONArray fullHistory = loadHistory(chatId);
            JSONArray systemDirectives = new JSONArray();
            JSONArray chatMessages = new JSONArray();

            for (int i = 0; i < fullHistory.length(); i++) {
                JSONObject msg = fullHistory.getJSONObject(i);
                String role = msg.optString("role", "");
                if ("system".equals(role)) {
                    systemDirectives.put(msg);
                } else {
                    chatMessages.put(msg);
                }
            }

            for (int i = 0; i < systemDirectives.length(); i++) {
                messages.put(createMessageObj(
                        systemDirectives.getJSONObject(i).optString("role"),
                        systemDirectives.getJSONObject(i).optString("content")
                ));
            }

            int maxChatMessages = 80; 
            int startIdx = Math.max(0, chatMessages.length() - maxChatMessages);
            for (int i = startIdx; i < chatMessages.length(); i++) {
                messages.put(createMessageObj(
                        chatMessages.getJSONObject(i).optString("role"),
                        chatMessages.getJSONObject(i).optString("content")
                ));
            }

            messages.put(createMessageObj("user", "待翻译中文：" + text));

            return callChatMessages(messages);
        } catch (JSONException e) {
            throw new IOException("构建Messages失败");
        }
    }

    // ──────────────────────────────────────
    // 聊天历史管理 (★ 修复了多线程与无限膨胀 Bug)
    // ──────────────────────────────────────
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
            } catch (Exception e) {
                return new JSONArray();
            }
        }
    }

    public static void appendHistory(String chatId, String role, String content) {
        if (content == null || content.isEmpty()) return;
        synchronized (fileLock) { // ★ 线程排队锁
            try {
                JSONArray history = loadHistory(chatId);
                JSONObject entry = new JSONObject();
                entry.put("role", role);
                
                // 单条上限 1000 字
                String display = content.length() > 1000 ? content.substring(0, 1000) : content;
                entry.put("content", display);
                history.put(entry);

                // ★ 物理限容：硬盘文件永远只保留最新的 100 条记录，杜绝 OOM 闪退
                if (history.length() > 100) {
                    JSONArray trimmed = new JSONArray();
                    int start = history.length() - 100;
                    for (int i = start; i < history.length(); i++) {
                        trimmed.put(history.get(i));
                    }
                    history = trimmed;
                }

                File f = historyFile(chatId);
                f.getParentFile().mkdirs();
                try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
                    w.write(history.toString());
                }
            } catch (Exception ignored) {}
        }
    }

    public static List<String[]> loadHistoryForDisplay(String chatId) {
        List<String[]> list = new ArrayList<>();
        JSONArray history = loadHistory(chatId);
        for (int i = 0; i < history.length(); i++) {
            try {
                JSONObject obj = history.getJSONObject(i);
                String role = obj.optString("role", "");
                String content = obj.optString("content", "");
                
                // 去除 Base64 脏数据保护 UI
                if (content.contains("[IMAGE_BASE64:")) {
                    int start = content.indexOf("[IMAGE_BASE64:");
                    int end = content.indexOf("]", start);
                    if (end != -1) {
                        content = content.substring(0, start) + "[附图]" + content.substring(end + 1);
                    }
                }

                if ("user".equals(role)) list.add(new String[]{"对方", content});
                else if ("assistant".equals(role)) list.add(new String[]{"我", content});
                else list.add(new String[]{"指令", content});
            } catch (Exception ignored) {}
        }
        return list;
    }

    // 省略 API调用/URL处理/缓存等与之前一样的辅助代码...
    // (保留原本的 toChinese, callChatMessages, fetchModels 等功能)
    public static String toChinese(String text) throws IOException {
        if (!needTranslateToChinese(text)) return text;
        String prompt = receivePrompt + "\n\n需要翻译的外语消息：\n" + text;
        return callChatSimple(prompt);
    }
    public static String fromChinese(String text, String lang) throws IOException {
        return callChatSimple("把以下中文翻译成" + lang + "：" + text);
    }
    public static String translateTest(String text, String lang) throws IOException {
        if (isChineseOnly(text)) return callChatSimple("把以下中文翻译成" + lang + "：" + text);
        return toChinese(text);
    }
    private static String callChatSimple(String prompt) throws IOException {
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
        } catch (JSONException e) { throw new IOException("构建失败"); }
    }
    private static String callChatMessages(JSONArray messages) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 2000);
            body.put("messages", messages);
            return executeRequest(body);
        } catch (JSONException e) { throw new IOException("构建失败"); }
    }
    private static String executeRequest(JSONObject body) throws IOException {
        Request req = new Request.Builder()
                .url(fixUrl(apiUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            return new JSONObject(resp.body().string()).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content").trim();
        } catch (JSONException e) { throw new IOException("JSON:" + e.getMessage()); }
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
    private static void loadCache() {
        if (!cacheFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(cacheFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                int idx = line.indexOf("|||");
                if (idx > 0) cache.put(line.substring(0, idx), line.substring(idx + 3));
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
    public static String getCached(String key) { return cache.get(key); }
    public static void cacheResult(String key, String value) { cache.put(key, value); saveCache(); }
    private static void loadPrompts() {
        // 省略读取代码，保持原有逻辑
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
                if (cur.equals("UK")) promptUK = sb.toString().trim();
                else if (cur.equals("KO")) promptKO = sb.toString().trim();
                else if (cur.equals("ES")) promptES = sb.toString().trim();
                r.close();
            }
        } catch (Exception ignored) {}
        if (receivePrompt.isEmpty()) receivePrompt = "你是一个社交情报传译员。代入对方身份，将外语翻译成地道有呼吸感的中文。";
        if (promptEN.isEmpty()) promptEN = "你是社交嘴替。把中文转成地道英语口语，4版本。";
        // 略...
    }
    public static void savePrompts(String zh, String en, String ru, String uk, String ko, String es) {
        receivePrompt = zh; promptEN = en; promptRU = ru; promptUK = uk; promptKO = ko; promptES = es;
    }
}

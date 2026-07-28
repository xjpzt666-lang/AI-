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

    // 翻译缓存
    public static final Map<String, String> cache = new ConcurrentHashMap<>();
    private static File cacheFile;

    // Prompt 文件
    private static File promptFile;

    // 4种语言的 System Prompt
    public static String receivePrompt = "";
    public static String promptEN = "";
    public static String promptRU = "";
    public static String promptUK = "";

    // ──────────────────────────────────────
    // 初始化
    // ──────────────────────────────────────

    public static void init(String key, String url, String m) {
        apiKey = key;
        apiUrl = url;
        model = m;

        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();

        cacheFile = new File("/data/data/com.hellotalk/files/htai_cache.txt");
        promptFile = new File("/data/local/tmp/htai_prompts.txt");

        loadCache();
        loadPrompts();

        Log.i(TAG, "缓存:" + cache.size() + "条");
    }

    public static void initForFetch(String key, String url) {
        apiKey = key;
        apiUrl = url;

        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build();
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
    // Prompt 加载
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
                    } else {
                        sb.append(line).append("\n");
                    }
                }
                if (cur.equals("UK")) promptUK = sb.toString().trim();
                r.close();
            }
        } catch (Exception ignored) {}

        // 默认值
        if (receivePrompt.isEmpty())
            receivePrompt = "你是一个社交情报传译员。代入对方身份，将外语翻译成地道有呼吸感的中文。";
        if (promptEN.isEmpty())
            promptEN = "你是社交嘴替。把中文转成地道英语口语，4版本：自然/暖男/奶狗/推荐。格式：外文|中文大意|标签。";
        if (promptRU.isEmpty())
            promptRU = "你是社交嘴替。把中文转成地道俄语口语。4版本。格式：外文|中文大意|标签。";
        if (promptUK.isEmpty())
            promptUK = "你是社交嘴替。把中文转成地道乌克兰语口语。4版本。格式：外文|中文大意|标签。";
    }

    public static void savePrompts(String zh, String en, String ru, String uk) {
        receivePrompt = zh;
        promptEN = en;
        promptRU = ru;
        promptUK = uk;
    }

    // ──────────────────────────────────────
    // 中文检测
    // ──────────────────────────────────────

    public static boolean isChinese(String s) {
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
    // 翻译
    // ──────────────────────────────────────

    public static String toChinese(String text) throws IOException {
        text = text.trim();
        if (text.isEmpty()) return text;
        if (isChinese(text)) return text;
        String prompt = "把以下消息翻译成地道的中文：" + text;
        return callChatSimple(prompt);
    }

    public static String fromChinese(String text, String lang) throws IOException {
        text = text.trim();
        if (text.isEmpty()) return text;
        String prompt = "把以下中文翻译成" + lang + "：" + text;
        return callChatSimple(prompt);
    }

    public static String translateTest(String text, String lang) throws IOException {
        if (isChinese(text)) {
            String prompt = "把以下中文翻译成" + lang + "：" + text;
            return callChatSimple(prompt);
        } else {
            return toChinese(text);
        }
    }

    public static String translateWithHistory(String text, String langCode, int chatId) throws IOException {
        try {
            JSONArray messages = new JSONArray();

            // System prompt（根据语言选择）
            String sysPrompt;
            switch (langCode) {
                case "ru":
                    sysPrompt = promptRU;
                    break;
                case "uk":
                    sysPrompt = promptUK;
                    break;
                default:
                    sysPrompt = promptEN;
                    break;
            }

            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", sysPrompt);
            messages.put(sys);

            // 加载历史
            JSONArray history = loadHistory(chatId);
            for (int i = 0; i < history.length(); i++) {
                messages.put(history.get(i));
            }

            // 用户消息
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
        // 自动补全
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
    // 文本提取
    // ──────────────────────────────────────

    public static String extractText(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.startsWith("{")) {
            try {
                return new JSONObject(s).optString("text", s);
            } catch (Exception e) {
                return s;
            }
        }
        return s;
    }

    // ──────────────────────────────────────
    // 多版本解析
    // ──────────────────────────────────────

    public static List<String> parseVersions(String text) {
        List<String> list = new ArrayList<>();
        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // 匹配 "1) xxx" 或 "1. xxx" 格式
            if (line.matches("^\\d[\\.\\)].*")) {
                // 去掉前面的数字编号
                String content = line.replaceFirst("^\\d[\\.\\)]\\s*", "");
                // 清理括号
                content = content.replace("（", "").replace("）", "")
                        .replace("【", "").replace("】", "");
                list.add(content.trim());
            }
        }
        if (list.isEmpty() && !text.isEmpty()) {
            list.add(text);
        }
        return list;
    }

    // ──────────────────────────────────────
    // 聊天历史
    // ──────────────────────────────────────

    private static File historyFile(int chatId) {
        return new File("/data/data/com.hellotalk/files/htai_hist_" + chatId + ".json");
    }

    public static JSONArray loadHistory(int chatId) {
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

    public static void appendHistory(int chatId, String role, String content) {
        if (content == null || content.isEmpty()) return;
        try {
            JSONArray history = loadHistory(chatId);
            JSONObject entry = new JSONObject();
            entry.put("role", role);
            // 截断过长的内容
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

    public static List<String[]> loadHistoryForDisplay(int chatId) {
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

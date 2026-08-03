package com.aihellotalk;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    // key = msgId, value = [foreign, chinese]
    public static final Map<String, String[]> cache = new ConcurrentHashMap<>();
    public static final Map<String, String> foreignToChinese = new ConcurrentHashMap<>();
    public static final Map<String, String> chineseToForeign = new ConcurrentHashMap<>();

    // 我发送出去时，用外语文本反查中文草稿
    public static final Map<String, String> mySentDrafts = new ConcurrentHashMap<>();

    private static File cacheFile;
    private static File promptFile;

    public static String receivePrompt = "";
    public static String promptEN = "";
    public static String promptRU = "";
    public static String promptUK = "";
    public static String promptKO = "";
    public static String promptES = "";

    private static final File friendsFile = new File("/data/data/com.hellotalk/files/htai_friends.json");
    private static JSONObject friendsData = new JSONObject();

    private static final Object fileLock = new Object();

    private static final Pattern JAPANESE_PATTERN = Pattern.compile(
            "[\\u3040-\\u30FF\\uFF65-\\uFF9F\\u30FC]+"
    );

    // ═══════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════

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

    public static void cancelOngoingTranslation() {
        if (client != null) {
            try {
                client.dispatcher().cancelAll();
                Log.i(TAG, "已触发急停：切断所有底层翻译请求");
            } catch (Exception ignored) {
            }
        }
    }

    // ═══════════════════════════════════════════
    // 图片转 Base64（智能压缩防崩溃）
    // ═══════════════════════════════════════════

    private static String encodeFileToBase64(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) return null;

            // 1. 获取图片原始宽高，不加载到内存
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            // 2. 计算缩放比例 (限制最大宽高在 1024x1024 内，防止网络超时或OOM)
            options.inSampleSize = calculateInSampleSize(options, 1024, 1024);
            options.inJustDecodeBounds = false;

            // 3. 真正解码并转 Base64
            Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            if (bitmap == null) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos);
            byte[] bytes = baos.toByteArray();
            bitmap.recycle();

            return Base64.encodeToString(bytes, Base64.NO_WRAP);
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

    // ═══════════════════════════════════════════
    // 好友信息与语言判断
    // ═══════════════════════════════════════════

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
        } catch (JSONException ignored) {
        }
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
        } catch (JSONException ignored) {
        }
        return list;
    }

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

    public static boolean needTranslateToChinese(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (containsJapanese(text)) return false;
        if (isChineseOnly(text)) return false;
        return true;
    }

    private static String stripFlipMarks(String s) {
        if (s == null) return null;
        return s.replaceAll("([ ]?[🌐🔄]+)$", "").trim();
    }

    // ═══════════════════════════════════════════
    // 构造消息对象（全新多模态解析支持）
    // ═══════════════════════════════════════════

    private static JSONObject createMessageObj(String role, String content) throws JSONException {
        JSONObject msgObj = new JSONObject();
        msgObj.put("role", role);

        // 如果剧本中包含了我们在 ChatHook 拦截到的本地图片标签
        if (content.contains("[LOCAL_IMAGE:")) {
            JSONArray contentArray = new JSONArray();
            
            Matcher m = Pattern.compile("\\[LOCAL_IMAGE:(.*?)\\]").matcher(content);
            StringBuffer cleanText = new StringBuffer();
            List<String> base64Images = new ArrayList<>();

            while (m.find()) {
                String path = m.group(1).trim();
                String b64 = encodeFileToBase64(path);
                if (b64 != null) {
                    base64Images.add(b64);
                    // 替换占位符，告知AI图已发
                    m.appendReplacement(cleanText, "[系统提示：对方发送了一张图片，该图片已附带在视觉通道中供你查看]");
                } else {
                    m.appendReplacement(cleanText, "[系统提示：对方发送了一张图片，但本地读取失败]");
                }
            }
            m.appendTail(cleanText);

            // 1. 组装纯文本部分
            JSONObject txtObj = new JSONObject();
            txtObj.put("type", "text");
            txtObj.put("text", cleanText.toString());
            contentArray.put(txtObj);

            // 2. 将提取成功的 Base64 拼接到消息体，实现 OpenAI Vision 格式
            for (String b64 : base64Images) {
                JSONObject imgObj = new JSONObject();
                imgObj.put("type", "image_url");
                JSONObject urlObj = new JSONObject();
                urlObj.put("url", "data:image/jpeg;base64," + b64);
                imgObj.put("image_url", urlObj);
                contentArray.put(imgObj);
            }

            msgObj.put("content", contentArray);
        } else {
            // 普通纯文本消息
            msgObj.put("content", content);
        }
        return msgObj;
    }

    // ═══════════════════════════════════════════
    // 翻译入口 (完美融合括号提问双模式)
    // ═══════════════════════════════════════════

    public static String toChinese(String text) throws IOException {
        return toChinese(text, "0");
    }

    public static String toChinese(String text, String chatId) throws IOException {
        text = text.trim();
        if (text.isEmpty()) return text;
        if (!needTranslateToChinese(text)) return text;

        try {
            JSONArray messages = new JSONArray();
            String sysPrompt = receivePrompt + "\n\n【系统隐性指令】：仅用于辅助理解上下文，不要脑补和加戏。只直译最后一条外语消息。";
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
                    scriptBuilder.append("对方: ").append(content).append("\n");
                    hasContext = true;
                } else if ("assistant".equals(role)) {
                    scriptBuilder.append("我: ").append(content).append("\n");
                    hasContext = true;
                } else if ("system".equals(role) && content.contains("[LOCAL_IMAGE:")) {
                    scriptBuilder.append("我(注入行为): ").append(content).append("\n");
                    hasContext = true;
                }
            }

            if (!hasContext) {
                scriptBuilder.append("（暂无有效上下文）\n");
            }

            scriptBuilder.append("\n【请翻译以下最新外语消息】\n").append(text);
            messages.put(createMessageObj("user", scriptBuilder.toString()));
            return callChatMessages(messages);
        } catch (JSONException e) {
            return callChatSimple(receivePrompt + "\n\n需要翻译的外语消息：\n" + text);
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

            // ★ 核心魔法：括号双模解析机制
            String universalProtocol = sysPrompt + "\n\n【系统最高强制协议（含多模态视觉与括号指令解析）】：\n" +
                    "1. 下方是【历史聊天剧本】。如果剧本中出现“[图片已成功附带在视觉通道]”，代表你已经看到了该图片。\n" +
                    "2. 剧本后，<translate> 标签内包裹的是我刚刚在输入框打出的【最新文字】。请严格判断文字格式，执行以下两种模式之一：\n\n" +
                    "【模式A：纯对话求助模式（不翻译）】\n" +
                    "► 触发条件：<translate> 内的文字**全部**被括号（() 或 （））包裹，括号外没有任何其他字符。例如：`(这张图片里是哪部动漫？)` 或 `（她这句话是生气了吗）`。\n" +
                    "► 你的任务：不需要进行任何外语翻译！直接作为一个无所不知的AI助手，观察上下文或图片，回答我的提问。\n" +
                    "► 格式强制：在 ===== 上半部分给出你的详细解答/分析。下半部分直接写一个占位选项（格式如下）。\n" +
                    "回答示例：\n" +
                    "图片里是《火影忍者》，主要角色有鸣人、佐助...\n" +
                    "====================\n" +
                    "Got it|(已为你解答，请查看上方区域)|AI助手\n\n" +
                    "【模式B：标准翻译 + 附加指令模式】\n" +
                    "► 触发条件：<translate> 内有正常的中文（不在括号里）。括号可能作为附加要求存在。例如：`看起来挺酷的（说说图片是什么动漫）` 或 `哈哈没关系`。\n" +
                    "► 你的任务：将括号外的中文翻译为地道外语。如果带有括号，括号里的内容是给你的“风格要求”或“附加提问”。如果括号内提出了问题（比如问图片内容），你必须在 ===== 上半部分先给出解答！下半部分严格给出4个翻译选项，【严禁】把括号里的中文字面意思翻译过去！\n" +
                    "回答示例：\n" +
                    "解答：图片里是《火影忍者》。接下来为你翻译“看起来挺酷的”：\n" +
                    "====================\n" +
                    "That looks pretty cool!|那看起来挺酷的！|自然随性\n" +
                    "The art style is amazing.|画风看起来很棒。|赞美\n" +
                    "Wow, so cool!|哇，太酷了！|热情\n" +
                    "It looks awesome.|它看起来棒极了。|简洁\n";

            messages.put(createMessageObj("system", universalProtocol));

            JSONArray fullHistory = loadHistory(chatId);
            StringBuilder scriptBuilder = new StringBuilder();
            scriptBuilder.append("【历史聊天剧本】\n");

            int maxChatMessages = 80;
            int startIdx = Math.max(0, fullHistory.length() - maxChatMessages);

            for (int i = startIdx; i < fullHistory.length(); i++) {
                JSONObject msg = fullHistory.getJSONObject(i);
                String role = msg.optString("role", "");
                String content = msg.optString("content", "");

                if ("user".equals(role)) {
                    scriptBuilder.append("对方: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    scriptBuilder.append("我: ").append(content).append("\n");
                } else if ("system".equals(role) && content.contains("[LOCAL_IMAGE:")) {
                    scriptBuilder.append("我(收到系统数据): ").append(content).append("\n");
                }
            }

            scriptBuilder.append("\n【我的最新输入】\n");
            scriptBuilder.append("<translate>\n").append(text).append("\n</translate>");

            messages.put(createMessageObj("user", scriptBuilder.toString()));

            return callChatMessages(messages);
        } catch (JSONException e) {
            throw new IOException("构建Messages失败");
        }
    }

    // ═══════════════════════════════════════════
    // 网络层请求
    // ═══════════════════════════════════════════

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
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            return new JSONObject(resp.body().string())
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim();
        } catch (JSONException e) {
            throw new IOException("JSON:" + e.getMessage());
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

    // ═══════════════════════════════════════════
    // 缓存与其他功能层
    // ═══════════════════════════════════════════

    private static void loadCache() {
        if (!cacheFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(cacheFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\\|\\|\\|");
                if (parts.length >= 3) {
                    String foreign = stripFlipMarks(parts[1]);
                    String chinese = stripFlipMarks(parts[2]);
                    cache.put(parts[0], new String[]{foreign, chinese});
                    foreignToChinese.put(foreign, chinese);
                    chineseToForeign.put(chinese, foreign);
                }
            }
        } catch (Exception ignored) {}
    }

    public static void saveCache() {
        try {
            cacheFile.getParentFile().mkdirs();
            try (BufferedWriter w = new BufferedWriter(new FileWriter(cacheFile))) {
                for (Map.Entry<String, String[]> e : cache.entrySet()) {
                    String foreign = stripFlipMarks(e.getValue()[0]);
                    String chinese = stripFlipMarks(e.getValue()[1]);
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
            if (clean.contains(key) || key.contains(clean)) return entry.getValue();
        }
        return null;
    }

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
                if (cur.equals("UK")) promptUK = sb.toString().trim();
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

    public static void appendHistory(String chatId, String msgId, String role, String content) {
        appendHistory(chatId, msgId, role, content, System.currentTimeMillis(), null);
    }

    public static void appendHistory(String chatId, String msgId, String role, String content, long timestamp, String quotedText) {
        if (content == null || content.isEmpty()) return;

        synchronized (fileLock) {
            try {
                JSONArray history = loadHistory(chatId);
                if (msgId != null && !msgId.isEmpty()) {
                    for (int i = 0; i < history.length(); i++) {
                        JSONObject obj = history.getJSONObject(i);
                        if (msgId.equals(obj.optString("msgId"))) return;
                    }
                }
                if (quotedText != null && !quotedText.isEmpty()) content = "（针对我的原话：\"" + quotedText + "\" 进行了回复）\n" + content;

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

                if (history.length() > 100) {
                    JSONArray trimmed = new JSONArray();
                    for (int i = history.length() - 100; i < history.length(); i++) trimmed.put(history.get(i));
                    history = trimmed;
                }

                File f = historyFile(chatId);
                f.getParentFile().mkdirs();
                try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) { w.write(history.toString()); }
            } catch (Exception ignored) {}
        }
    }
}

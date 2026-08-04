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

    public static final Map<String, String[]> cache = new ConcurrentHashMap<>();
    public static final Map<String, String> foreignToChinese = new ConcurrentHashMap<>();
    public static final Map<String, String> chineseToForeign = new ConcurrentHashMap<>();
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

    private static final Pattern JAPANESE_PATTERN = Pattern.compile("[\\u3040-\\u30FF\\uFF65-\\uFF9F\\u30FC]+");
    private static final Pattern LOCAL_IMAGE_PATTERN = Pattern.compile("\\[LOCAL_IMAGE:(.*?)\\]");
    private static final Pattern QUOTED_LOCAL_IMAGE_PATTERN = Pattern.compile("\\[QUOTED_LOCAL_IMAGE:(.*?)\\]");
    private static final Pattern PURE_BRACKET_MODE_PATTERN = Pattern.compile("\\[PURE_BRACKET_MODE\\]");
    private static final Pattern QUOTED_IMAGE_MISSING_PATTERN = Pattern.compile("\\[QUOTED_IMAGE_BUT_PATH_MISSING\\]");

    // ★ 核心改动：设置每次请求的总 Base64 字符上限 (约 675KB，保护网关)
    private static final int MAX_TOTAL_BASE64_CHARS = 900_000;

    public static void init(String key, String url, String m) {
        apiKey = key;
        apiUrl = url;
        model = m;

        // ★ 核心改动：放宽视觉大包上传的超时时间
        client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
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
            } catch (Exception ignored) {}
        }
    }

    // ★ 核心改动：多轮阶梯压缩，死守 90KB 单图体积底线
    public static String encodeFileToBase64(String path) {
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

            return Base64.encodeToString(bestBytes, Base64.NO_WRAP);
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

        // ★ 核心改动：引入总体积预算，超额则跳过图片，保住请求
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

    public static String toChinese(String text) throws IOException {
        return toChinese(text, "0");
    }

    public static String toChinese(String text, String chatId) throws IOException {
        text = text.trim();
        if (text.isEmpty()) return text;
        if (!needTranslateToChinese(text)) return text;

        try {
            JSONArray messages = new JSONArray();

            String sysPrompt = receivePrompt +
                    "\n\n【系统隐性协议（多模态）】：" +
                    "\n1. 你可能会同时看到文本和图片。" +
                    "\n2. 如果消息中带有[背景上下文图片]，那是最近聊天背景，用于帮助理解上下文。" +
                    "\n3. 如果消息中带有[当前回复目标图]，那是当前重点图，优先关注这张。" +
                    "\n4. 你只需要把最后一条外语消息翻译成中文，必要时结合图片消歧。" +
                    "\n5. 不要描述你收到了图片，也不要解释协议。";

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
                }
            }

            if (!hasContext) scriptBuilder.append("（暂无有效上下文）\n");
            scriptBuilder.append("\n【请翻译以下最新外语消息】\n").append(text);

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

            String universalProtocol = sysPrompt +
                    "\n\n【系统最高强制协议（多模态视觉与指令解析）】：" +
                    "\n1. 下方是【历史聊天剧本】。如果消息里附带了图片，你已经可以看到它们。" +
                    "\n2. [背景上下文图片] = 最近聊天背景，仅用于帮助理解上下文。" +
                    "\n3. [当前回复目标图] = 我此刻正在回复的焦点图，优先分析这张。" +
                    "\n4. 如果提示中出现“当前回复目标是一张图片，但本地文件路径未获取到”，说明你不能把背景图误认为焦点图，必须保守回答。" +
                    "\n5. 剧本后，<translate> 标签内包裹的是我刚刚输入的【最新文字】。请严格判断格式，执行以下两种模式之一：" +
                    "\n6. 【绝对服从】：如果用户消息中出现【强制模式】MODE_A_ONLY，你必须无条件执行【模式A】，严禁出现任何翻译选项！" +

                    "\n\n【模式A：纯对话求助模式（不翻译）】" +
                    "\n► 触发条件（任一满足即可）：" +
                    "\n  a. <translate> 内的文字全部被括号（() 或 （））包裹；" +
                    "\n  b. 提示文本中明确出现“【强制模式】MODE_A_ONLY”。" +
                    "\n► 你的身份：你是独立AI军师，不扮演我或对方。" +
                    "\n► 任务：结合焦点图优先、背景图次之，直接客观回答我的问题。" +
                    "\n► 严禁：严禁输出4个翻译选项！严禁把括号问题翻译成外语！" +
                    "\n► 输出格式：上半部分写详细解答，下半部分直接给一个占位选项。" +

                    "\n\n【模式B：标准翻译 + 附加指令/提问模式】" +
                    "\n► 触发条件：存在正常中文正文，且不是模式A。" +
                    "\n► 任务：" +
                    "\n  1. 括号内是给你的私下要求、风格要求、补充提问，严禁逐字翻译进外语。" +
                    "\n  2. 如括号内包含关于图片的提问，请先在 ===== 上半部分回答问题。" +
                    "\n  3. 然后严格结合上下文，把括号外正文翻译成地道目标语言。" +
                    "\n► 输出格式：上半部分写分析/解答（若无可简短），下半部分输出4个翻译选项。";

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

    // ★ 核心改动：加入日志打印 Request Body 长度，方便查明网关是否超载
    private static String executeRequest(JSONObject body) throws IOException {
        String bodyStr = body.toString();
        Log.i(TAG, "request body chars = " + bodyStr.length());
        
        Request req = new Request.Builder()
                .url(fixUrl(apiUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(bodyStr, JSON_TYPE))
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

                if (history.length() > 100) {
                    JSONArray trimmed = new JSONArray();
                    for (int i = history.length() - 100; i < history.length(); i++) trimmed.put(history.get(i));
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
}

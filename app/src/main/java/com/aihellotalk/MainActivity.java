package com.aihellotalk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.drawerlayout.widget.DrawerLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import okhttp3.*;

public class MainActivity extends Activity {

    private DrawerLayout drawerLayout;
    private LinearLayout messageContainer;
    private ScrollView messageScrollView;
    private EditText inputBox;
    private Button sendBtn;
    private Button attachBtn;
    private LinearLayout drawerContent;

    private String currentChatId = "";
    private String currentChatName = "";
    private List<ChatSession> chatSessions = new ArrayList<>();

    private String lastUserMessage = "";

    private OkHttpClient httpClient;
    private Handler mainHandler;
    private DatabaseHelper dbHelper;

    private static final int MAX_HISTORY_ROUNDS = 100;

    private String cachedApiKey = "";
    private String cachedApiUrl = "";
    private String cachedModel = "";
    private boolean configLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        mainHandler = new Handler(Looper.getMainLooper());
        dbHelper = new DatabaseHelper(this);

        loadConfigOnce();
        loadChatSessions();

        drawerLayout = new DrawerLayout(this);

        LinearLayout mainContent = new LinearLayout(this);
        mainContent.setOrientation(LinearLayout.VERTICAL);
        mainContent.setBackgroundColor(Color.WHITE);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(16, 16, 16, 16);
        topBar.setBackgroundColor(Color.parseColor("#F5F5F5"));

        Button leftMenuBtn = new Button(this);
        leftMenuBtn.setText("☰");
        leftMenuBtn.setTextSize(20f);
        leftMenuBtn.setBackgroundColor(Color.TRANSPARENT);
        leftMenuBtn.setOnClickListener(v -> drawerLayout.openDrawer(Gravity.LEFT));

        TextView title = new TextView(this);
        title.setText("HT AI 聊天");
        title.setTextSize(18f);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button rightMenuBtn = new Button(this);
        rightMenuBtn.setText("⋮");
        rightMenuBtn.setTextSize(24f);
        rightMenuBtn.setBackgroundColor(Color.TRANSPARENT);
        rightMenuBtn.setOnClickListener(this::showPopupMenu);

        topBar.addView(leftMenuBtn);
        topBar.addView(title);
        topBar.addView(rightMenuBtn);
        mainContent.addView(topBar);

        TextView chatTitle = new TextView(this);
        chatTitle.setText("当前: 暂无对话");
        chatTitle.setTag("chatTitle");
        chatTitle.setPadding(16, 12, 16, 12);
        chatTitle.setTextSize(14f);
        chatTitle.setTextColor(Color.GRAY);
        chatTitle.setBackgroundColor(Color.parseColor("#F9F9F9"));
        mainContent.addView(chatTitle);

        messageScrollView = new ScrollView(this);
        messageScrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        messageScrollView.setPadding(16, 8, 16, 8);

        messageContainer = new LinearLayout(this);
        messageContainer.setOrientation(LinearLayout.VERTICAL);
        messageScrollView.addView(messageContainer);
        mainContent.addView(messageScrollView);

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(8, 8, 8, 8);
        bottomBar.setBackgroundColor(Color.parseColor("#F0F0F0"));

        attachBtn = new Button(this);
        attachBtn.setText("+");
        attachBtn.setTextSize(24f);
        attachBtn.setBackgroundColor(Color.TRANSPARENT);
        attachBtn.setOnClickListener(v -> showAttachMenu());

        inputBox = new EditText(this);
        inputBox.setHint("输入消息...");
        inputBox.setBackgroundColor(Color.WHITE);
        inputBox.setPadding(16, 12, 16, 12);
        inputBox.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendBtn = new Button(this);
        sendBtn.setText("发送");
        sendBtn.setOnClickListener(v -> sendMessage());

        bottomBar.addView(attachBtn);
        bottomBar.addView(inputBox);
        bottomBar.addView(sendBtn);
        mainContent.addView(bottomBar);

        drawerLayout.addView(mainContent);

        drawerContent = new LinearLayout(this);
        drawerContent.setOrientation(LinearLayout.VERTICAL);
        drawerContent.setPadding(20, 50, 20, 20);
        drawerContent.setBackgroundColor(Color.parseColor("#FAFAFA"));

        DrawerLayout.LayoutParams lp = new DrawerLayout.LayoutParams(dpToPx(280), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.LEFT;
        drawerContent.setLayoutParams(lp);

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("对话列表");
        drawerTitle.setTextSize(16f);
        drawerTitle.setTextColor(Color.BLACK);
        drawerTitle.setPadding(0, 0, 0, 20);
        drawerContent.addView(drawerTitle);

        refreshDrawerList();

        drawerLayout.addView(drawerContent);
        setContentView(drawerLayout);
    }

    private void loadConfigOnce() {
        SharedPreferences prefs = getSharedPreferences("htai_settings", MODE_PRIVATE);
        cachedApiKey = prefs.getString("api_key", "");
        cachedApiUrl = prefs.getString("api_url", "");
        cachedModel = prefs.getString("model", "");

        if (cachedApiKey.isEmpty()) cachedApiKey = readConfig("api_key");
        if (cachedApiUrl.isEmpty()) cachedApiUrl = readConfig("api_url");
        if (cachedModel.isEmpty()) cachedModel = readConfig("model");

        if (cachedApiUrl.isEmpty()) cachedApiUrl = "https://www.wintoken.dev";
        configLoaded = true;
    }

    private void loadChatSessions() {
        chatSessions.clear();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT id, name FROM chats ORDER BY updated_at DESC", null);
        while (cursor.moveToNext()) {
            String id = cursor.getString(0);
            String name = cursor.getString(1);
            chatSessions.add(new ChatSession(id, name));
        }
        cursor.close();
        db.close();
    }

    private void loadMessagesFromDb(String chatId) {
        messageContainer.removeAllViews();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT role, content FROM messages WHERE chat_id=? ORDER BY timestamp ASC",
                new String[]{chatId});
        while (cursor.moveToNext()) {
            String role = cursor.getString(0);
            String content = cursor.getString(1);
            displayMessage(role, content);
        }
        cursor.close();
        db.close();
    }

    private void saveMessageToDb(String chatId, String role, String content) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("chat_id", chatId);
        values.put("role", role);
        values.put("content", content);
        values.put("timestamp", System.currentTimeMillis());
        db.insert("messages", null, values);
        db.close();
    }

    private List<Message> getRecentHistory(String chatId, int maxRounds) {
        List<Message> history = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT role, content FROM messages WHERE chat_id=? ORDER BY timestamp ASC",
                new String[]{chatId});

        List<Message> allMessages = new ArrayList<>();
        while (cursor.moveToNext()) {
            String role = cursor.getString(0);
            String content = cursor.getString(1);
            allMessages.add(new Message(role, content));
        }
        cursor.close();
        db.close();

        int total = allMessages.size();
        int start = Math.max(0, total - maxRounds * 2);
        for (int i = start; i < total; i++) {
            history.add(allMessages.get(i));
        }
        return history;
    }

    private void showAttachMenu() {
        String[] options = {"📷 拍照", "🖼️ 相册", "📎 文件"};
        new AlertDialog.Builder(this)
                .setTitle("选择附件")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) Toast.makeText(this, "拍照功能待实现", Toast.LENGTH_SHORT).show();
                    else if (which == 1) Toast.makeText(this, "相册功能待实现", Toast.LENGTH_SHORT).show();
                    else if (which == 2) Toast.makeText(this, "文件功能待实现", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void refreshDrawerList() {
        while (drawerContent.getChildCount() > 1) {
            drawerContent.removeViewAt(1);
        }

        if (chatSessions.isEmpty()) {
            TextView emptyHint = new TextView(this);
            emptyHint.setText("发送第一条消息后自动创建");
            emptyHint.setTextSize(14f);
            emptyHint.setTextColor(Color.GRAY);
            emptyHint.setPadding(0, 40, 0, 20);
            drawerContent.addView(emptyHint);
        } else {
            for (ChatSession session : chatSessions) {
                TextView itemView = new TextView(this);
                itemView.setText("💬 " + session.name);
                itemView.setTextSize(16f);
                itemView.setPadding(20, 25, 20, 25);
                itemView.setTextColor(Color.BLACK);

                if (session.id.equals(currentChatId)) {
                    itemView.setBackgroundColor(Color.parseColor("#E3F2FD"));
                }

                itemView.setOnClickListener(v -> {
                    switchToChat(session);
                    drawerLayout.closeDrawers();
                });
                itemView.setOnLongClickListener(v -> {
                    String[] options = {"重命名", "删除"};
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("操作: " + session.name)
                            .setItems(options, (dialog, which) -> {
                                if (which == 0) showRenameDialog(session);
                                else if (which == 1) deleteChat(session);
                            })
                            .show();
                    return true;
                });
                drawerContent.addView(itemView);
            }
        }
    }

    private void deleteChat(ChatSession session) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete("messages", "chat_id=?", new String[]{session.id});
        db.delete("chats", "id=?", new String[]{session.id});
        db.close();

        chatSessions.remove(session);
        if (currentChatId.equals(session.id)) {
            currentChatId = "";
            currentChatName = "";
            ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前: 暂无对话");
            messageContainer.removeAllViews();
        }
        refreshDrawerList();
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
    }

    private void showRenameDialog(ChatSession session) {
        final EditText input = new EditText(this);
        input.setText(session.name);
        input.setSelection(session.name.length());
        new AlertDialog.Builder(this)
                .setTitle("重命名对话")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        SQLiteDatabase db = dbHelper.getWritableDatabase();
                        ContentValues cv = new ContentValues();
                        cv.put("name", newName);
                        db.update("chats", cv, "id=?", new String[]{session.id});
                        db.close();
                        session.name = newName;
                        refreshDrawerList();
                        Toast.makeText(MainActivity.this, "已重命名为: " + newName, Toast.LENGTH_SHORT).show();
                        if (currentChatId.equals(session.id)) {
                            currentChatName = newName;
                            ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前: " + currentChatName);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPopupMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenu().add(0, 1, 0, "💬 开启新对话");
        popup.getMenu().add(0, 2, 0, "⚙️ 设置/API配置");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                currentChatId = "";
                currentChatName = "";
                ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前: 暂无对话");
                messageContainer.removeAllViews();
                Toast.makeText(this, "已开启新对话", Toast.LENGTH_SHORT).show();
                return true;
            } else if (item.getItemId() == 2) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void switchToChat(ChatSession session) {
        currentChatId = session.id;
        currentChatName = session.name;
        TextView chatTitle = (TextView) drawerLayout.findViewWithTag("chatTitle");
        if (chatTitle != null) chatTitle.setText("当前: " + currentChatName);
        loadMessagesFromDb(currentChatId);
        refreshDrawerList();
    }

    private void sendMessage() {
        String text = inputBox.getText().toString().trim();
        if (text.isEmpty()) return;

        lastUserMessage = text;

        if (currentChatId.isEmpty()) {
            String chatName = text.length() > 10 ? text.substring(0, 10) + "..." : text;
            String chatId = "chat_" + System.currentTimeMillis();

            SQLiteDatabase db = dbHelper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("id", chatId);
            cv.put("name", chatName);
            cv.put("updated_at", System.currentTimeMillis());
            db.insert("chats", null, cv);
            db.close();

            ChatSession newSession = new ChatSession(chatId, chatName);
            chatSessions.add(newSession);
            currentChatId = chatId;
            currentChatName = chatName;

            TextView chatTitle = (TextView) drawerLayout.findViewWithTag("chatTitle");
            if (chatTitle != null) chatTitle.setText("当前: " + currentChatName);
            refreshDrawerList();
        }

        displayMessage("user", text);
        saveMessageToDb(currentChatId, "user", text);
        inputBox.setText("");

        String apiKey = cachedApiKey;
        String apiUrl = cachedApiUrl;
        String model = cachedModel;

        if (apiKey.isEmpty()) {
            displayMessage("system", "⚠️ 请先在设置中填写 API Key");
            saveMessageToDb(currentChatId, "system", "⚠️ 请先在设置中填写 API Key");
            return;
        }
        if (apiUrl.isEmpty()) {
            apiUrl = "https://www.wintoken.dev";
        }
        if (model.isEmpty()) {
            displayMessage("system", "⚠️ 请先在设置中选择模型");
            saveMessageToDb(currentChatId, "system", "⚠️ 请先在设置中选择模型");
            return;
        }

        if (!apiUrl.endsWith("/chat/completions")) {
            if (apiUrl.endsWith("/v1")) {
                apiUrl = apiUrl + "/chat/completions";
            } else if (!apiUrl.endsWith("/")) {
                apiUrl = apiUrl + "/v1/chat/completions";
            } else {
                apiUrl = apiUrl + "v1/chat/completions";
            }
        }

        displayMessage("system", "🤔 正在思考...");

        List<Message> history = getRecentHistory(currentChatId, MAX_HISTORY_ROUNDS);

        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);

            JSONArray messages = new JSONArray();
            for (Message msg : history) {
                JSONObject histMsg = new JSONObject();
                String apiRole = msg.role.equals("ai") ? "assistant" : msg.role;
                histMsg.put("role", apiRole);
                histMsg.put("content", msg.content);
                messages.put(histMsg);
            }
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", text);
            messages.put(userMessage);
            requestBody.put("messages", messages);

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    mainHandler.post(() -> {
                        removeLastSystemMessage();
                        displayMessage("system", "❌ 请求失败: " + e.getMessage());
                        saveMessageToDb(currentChatId, "system", "❌ 请求失败: " + e.getMessage());
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    mainHandler.post(() -> {
                        removeLastSystemMessage();
                        if (response.isSuccessful()) {
                            try {
                                JSONObject json = new JSONObject(responseBody);
                                String reply = json.getJSONArray("choices")
                                        .getJSONObject(0)
                                        .getJSONObject("message")
                                        .getString("content");
                                displayMessage("ai", reply);
                                saveMessageToDb(currentChatId, "ai", reply);
                            } catch (Exception e) {
                                displayMessage("system", "❌ 解析响应失败: " + e.getMessage());
                                saveMessageToDb(currentChatId, "system", "❌ 解析响应失败: " + e.getMessage());
                            }
                        } else {
                            displayMessage("system", "❌ 服务器错误 " + response.code() + ": " + responseBody);
                            saveMessageToDb(currentChatId, "system", "❌ 服务器错误 " + response.code() + ": " + responseBody);
                        }
                    });
                }
            });

        } catch (Exception e) {
            removeLastSystemMessage();
            displayMessage("system", "❌ 构建请求失败: " + e.getMessage());
            saveMessageToDb(currentChatId, "system", "❌ 构建请求失败: " + e.getMessage());
        }
    }

    private void regenerateAnswer() {
        if (lastUserMessage.isEmpty()) {
            Toast.makeText(this, "没有可重新回答的消息", Toast.LENGTH_SHORT).show();
            return;
        }
        removeLastAiMessage();
        inputBox.setText(lastUserMessage);
        sendMessage();
    }

    private void removeLastAiMessage() {
        for (int i = messageContainer.getChildCount() - 1; i >= 0; i--) {
            View child = messageContainer.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                if (row.getChildCount() > 0 && row.getChildAt(0) instanceof TextView) {
                    TextView bubble = (TextView) row.getChildAt(0);
                    String text = bubble.getText().toString();
                    if (!text.contains("正在思考") && !text.contains("⚠️") && !text.contains("❌")) {
                        if (bubble.getCurrentTextColor() == Color.parseColor("#E8E8E8") ||
                                bubble.getBackground() != null) {
                            messageContainer.removeViewAt(i);
                            return;
                        }
                    }
                }
            }
        }
    }

    private void displayMessage(String role, String content) {
        String filteredContent = content.replaceAll("[*\\-]", "");

        LinearLayout msgRow = new LinearLayout(this);
        msgRow.setOrientation(LinearLayout.HORIZONTAL);
        msgRow.setPadding(0, 8, 0, 8);

        if ("ai".equals(role)) {
            LinearLayout bubbleWrapper = new LinearLayout(this);
            bubbleWrapper.setOrientation(LinearLayout.VERTICAL);
            bubbleWrapper.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView bubble = new TextView(this);
            bubble.setText(filteredContent);
            bubble.setTextSize(15f);
            bubble.setPadding(16, 12, 16, 12);
            bubble.setLineSpacing(4f, 1f);
            bubble.setBackgroundColor(Color.parseColor("#E8E8E8"));
            bubble.setTextIsSelectable(true);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.65);
            bubble.setLayoutParams(lp);

            bubbleWrapper.addView(bubble);
            msgRow.addView(bubbleWrapper);

            Button rotateBtn = new Button(this);
            rotateBtn.setText("🔄");
            rotateBtn.setTextSize(18f);
            rotateBtn.setBackgroundColor(Color.TRANSPARENT);
            rotateBtn.setOnClickListener(v -> regenerateAnswer());
            msgRow.addView(rotateBtn);

            msgRow.setGravity(Gravity.START);
        } else if ("user".equals(role)) {
            TextView bubble = new TextView(this);
            bubble.setText(filteredContent);
            bubble.setTextSize(15f);
            bubble.setPadding(16, 12, 16, 12);
            bubble.setLineSpacing(4f, 1f);
            bubble.setBackgroundColor(Color.parseColor("#DCF8C6"));
            bubble.setTextIsSelectable(true);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.65);
            bubble.setLayoutParams(lp);

            LinearLayout spacer = new LinearLayout(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            msgRow.addView(spacer);
            msgRow.addView(bubble);
            msgRow.setGravity(Gravity.END);
        } else {
            TextView bubble = new TextView(this);
            bubble.setText(filteredContent);
            bubble.setTextSize(13f);
            bubble.setPadding(16, 12, 16, 12);
            bubble.setLineSpacing(4f, 1f);
            bubble.setBackgroundColor(Color.parseColor("#FFF3CD"));
            bubble.setTextIsSelectable(true);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.65);
            bubble.setLayoutParams(lp);

            msgRow.addView(bubble);
            msgRow.setGravity(Gravity.CENTER);
        }

        messageContainer.addView(msgRow);
        messageScrollView.post(() -> messageScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void removeLastSystemMessage() {
        if (messageContainer.getChildCount() == 0) return;
        View lastView = messageContainer.getChildAt(messageContainer.getChildCount() - 1);
        if (lastView instanceof LinearLayout) {
            LinearLayout lastRow = (LinearLayout) lastView;
            if (lastRow.getChildCount() > 0 && lastRow.getChildAt(0) instanceof TextView) {
                TextView lastBubble = (TextView) lastRow.getChildAt(0);
                String text = lastBubble.getText().toString();
                if (text.contains("正在思考") || text.contains("⚠️") || text.contains("❌")) {
                    messageContainer.removeViewAt(messageContainer.getChildCount() - 1);
                }
            }
        }
    }

    private String readConfig(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat /data/local/tmp/htai_config.txt"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(key + "=")) {
                    return line.substring(key.length() + 1).trim();
                }
            }
            p.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    static class ChatSession {
        String id;
        String name;
        ChatSession(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static class Message {
        String role;
        String content;
        Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    static class DatabaseHelper extends SQLiteOpenHelper {
        private static final String DB_NAME = "chat_history.db";
        private static final int DB_VERSION = 1;

        DatabaseHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE chats (" +
                    "id TEXT PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "chat_id TEXT NOT NULL, " +
                    "role TEXT NOT NULL, " +
                    "content TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, " +
                    "FOREIGN KEY (chat_id) REFERENCES chats(id))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS messages");
            db.execSQL("DROP TABLE IF EXISTS chats");
            onCreate(db);
        }
    }
}

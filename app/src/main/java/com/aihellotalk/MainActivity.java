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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.drawerlayout.widget.DrawerLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import okhttp3.*;

public class MainActivity extends Activity {

    private static final int REQUEST_CODE_PICK_IMAGE = 101;
    private static final int REQUEST_CODE_PICK_FILE = 102;

    private DrawerLayout drawerLayout;
    private LinearLayout messageContainer;
    private ScrollView messageScrollView;
    private EditText inputBox;
    private Button sendBtn;
    private Button attachBtn;
    private LinearLayout drawerContent;

    // 图片预览相关
    private LinearLayout imagePreviewBar;
    private ImageView previewImage;
    private ImageButton previewCloseBtn;
    private String pendingImageBase64 = "";

    // 模型切换 Spinner
    private Spinner modelSpinner;
    private List<String> modelList = new ArrayList<>();

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
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("htai_settings", MODE_PRIVATE);

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        mainHandler = new Handler(Looper.getMainLooper());
        dbHelper = new DatabaseHelper(this);

        loadConfigOnce();
        loadChatSessions();

        drawerLayout = new DrawerLayout(this);
        LinearLayout mainContent = new LinearLayout(this);
        mainContent.setOrientation(LinearLayout.VERTICAL);
        mainContent.setBackgroundColor(Color.WHITE);

        // 顶部栏
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

        // 当前对话标题
        TextView chatTitle = new TextView(this);
        chatTitle.setText("当前: 暂无对话");
        chatTitle.setTag("chatTitle");
        chatTitle.setPadding(16, 12, 16, 12);
        chatTitle.setTextSize(14f);
        chatTitle.setTextColor(Color.GRAY);
        chatTitle.setBackgroundColor(Color.parseColor("#F9F9F9"));
        mainContent.addView(chatTitle);

        // 消息滚动区
        messageScrollView = new ScrollView(this);
        messageScrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        messageScrollView.setPadding(16, 8, 16, 8);

        messageContainer = new LinearLayout(this);
        messageContainer.setOrientation(LinearLayout.VERTICAL);
        messageScrollView.addView(messageContainer);
        mainContent.addView(messageScrollView);

        // 图片预览条（默认隐藏）
        imagePreviewBar = new LinearLayout(this);
        imagePreviewBar.setOrientation(LinearLayout.HORIZONTAL);
        imagePreviewBar.setPadding(8, 4, 8, 4);
        imagePreviewBar.setBackgroundColor(Color.parseColor("#EEEEEE"));
        imagePreviewBar.setVisibility(View.GONE);

        previewImage = new ImageView(this);
        previewImage.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(56), dpToPx(56)));
        previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewImage.setPadding(4, 4, 4, 4);

        TextView previewName = new TextView(this);
        previewName.setText("已选择图片");
        previewName.setTextSize(13f);
        previewName.setTextColor(Color.DKGRAY);
        previewName.setGravity(Gravity.CENTER_VERTICAL);
        previewName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        previewName.setPadding(12, 0, 0, 0);
        previewName.setTag("previewName");

        previewCloseBtn = new ImageButton(this);
        previewCloseBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        previewCloseBtn.setBackgroundColor(Color.TRANSPARENT);
        previewCloseBtn.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)));
        previewCloseBtn.setOnClickListener(v -> clearImagePreview());

        imagePreviewBar.addView(previewImage);
        imagePreviewBar.addView(previewName);
        imagePreviewBar.addView(previewCloseBtn);
        mainContent.addView(imagePreviewBar);

        // 底部输入栏
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(8, 8, 8, 8);
        bottomBar.setBackgroundColor(Color.parseColor("#F0F0F0"));

        attachBtn = new Button(this);
        attachBtn.setText("+");
        attachBtn.setTextSize(26f);
        attachBtn.setBackgroundColor(Color.TRANSPARENT);
        attachBtn.setOnClickListener(v -> showAttachMenu());

        // ★★★ 模型切换 Spinner ★★★
        modelList = loadModelList();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, modelList);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner = new Spinner(this);
        modelSpinner.setAdapter(adapter);
        modelSpinner.setLayoutParams(new LinearLayout.LayoutParams(
                dpToPx(140), ViewGroup.LayoutParams.WRAP_CONTENT));

        // 设置当前选中的模型
        String currentModel = prefs.getString("model", "");
        if (!currentModel.isEmpty()) {
            for (int i = 0; i < modelList.size(); i++) {
                if (modelList.get(i).equals(currentModel)) {
                    modelSpinner.setSelection(i);
                    break;
                }
            }
        }

        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = modelList.get(position);
                cachedModel = selected;
                prefs.edit().putString("model", selected).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        inputBox = new EditText(this);
        inputBox.setHint("输入消息...");
        inputBox.setBackgroundColor(Color.WHITE);
        inputBox.setPadding(16, 12, 16, 12);
        inputBox.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendBtn = new Button(this);
        sendBtn.setText("发送");
        sendBtn.setOnClickListener(v -> sendMessage());

        bottomBar.addView(attachBtn);
        bottomBar.addView(modelSpinner);  // 在 + 和输入框之间
        bottomBar.addView(inputBox);
        bottomBar.addView(sendBtn);
        mainContent.addView(bottomBar);

        drawerLayout.addView(mainContent);

        // 侧滑菜单
        drawerContent = new LinearLayout(this);
        drawerContent.setOrientation(LinearLayout.VERTICAL);
        drawerContent.setPadding(20, 70, 20, 20);
        drawerContent.setBackgroundColor(Color.parseColor("#FAFAFA"));

        DrawerLayout.LayoutParams lp = new DrawerLayout.LayoutParams(dpToPx(300), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.LEFT;
        drawerContent.setLayoutParams(lp);

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("对话列表");
        drawerTitle.setTextSize(18f);
        drawerTitle.setTextColor(Color.BLACK);
        drawerTitle.setPadding(0, 0, 0, 28);
        drawerContent.addView(drawerTitle);

        refreshDrawerList();

        drawerLayout.addView(drawerContent);
        setContentView(drawerLayout);
    }

    // ★★★ 加载模型列表 ★★★
    private List<String> loadModelList() {
        List<String> list = new ArrayList<>();
        String saved = prefs.getString("model_list", "");
        if (!saved.isEmpty()) {
            String[] arr = saved.split(",");
            for (String s : arr) {
                list.add(s.trim());
            }
        }
        // 如果列表为空，添加默认模型
        if (list.isEmpty()) {
            String defaultModel = prefs.getString("model", "deepseek-v4");
            list.add(defaultModel);
        }
        return list;
    }

    private void clearImagePreview() {
        pendingImageBase64 = "";
        previewImage.setImageBitmap(null);
        imagePreviewBar.setVisibility(View.GONE);
        inputBox.setHint("输入消息...");
    }

    private void showImagePreview(Bitmap thumb, String fileName) {
        previewImage.setImageBitmap(thumb);
        TextView nameView = (TextView) imagePreviewBar.findViewWithTag("previewName");
        if (nameView != null) nameView.setText("已选择: " + fileName);
        imagePreviewBar.setVisibility(View.VISIBLE);
    }

    private String imageUriToBase64(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;
            Bitmap original = BitmapFactory.decodeStream(inputStream);
            inputStream.close();
            if (original == null) return null;

            Bitmap thumb = Bitmap.createScaledBitmap(original, dpToPx(112), dpToPx(112), true);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            original.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            byte[] imageBytes = baos.toByteArray();
            original.recycle();

            String fileName = getFileName(uri);
            showImagePreview(thumb, fileName);
            return Base64.encodeToString(imageBytes, Base64.NO_WRAP);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void loadConfigOnce() {
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
        Cursor cursor = db.rawQuery("SELECT role, content FROM messages WHERE chat_id=? ORDER BY timestamp ASC", new String[]{chatId});
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
        Cursor cursor = db.rawQuery("SELECT role, content FROM messages WHERE chat_id=? ORDER BY timestamp ASC", new String[]{chatId});
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
        for (int i = start; i < total; i++) history.add(allMessages.get(i));
        return history;
    }

    private void showAttachMenu() {
        String[] options = {"🖼️ 相册", "📎 文件"};
        new AlertDialog.Builder(this)
                .setTitle("选择附件")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) pickImage();
                    else if (which == 1) pickFile();
                })
                .show();
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        if (requestCode == REQUEST_CODE_PICK_IMAGE) {
            String base64 = imageUriToBase64(uri);
            if (base64 != null) {
                pendingImageBase64 = base64;
                String fileName = getFileName(uri);
                inputBox.setHint("已选择图片: " + fileName + "，输入描述后发送");
                Toast.makeText(this, "已选择图片，输入文字后发送", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_CODE_PICK_FILE) {
            String fileContent = readTextFile(uri);
            if (fileContent != null) {
                String fileName = getFileName(uri);
                String fileInfo = "用户上传了文件：" + (fileName != null ? fileName : "未知文件") + "\n文件内容如下：\n" + fileContent;
                sendAttachmentAsMessage(fileInfo);
            } else {
                Toast.makeText(this, "无法读取文件内容", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String getFileName(Uri uri) {
        String fileName = null;
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex >= 0) fileName = cursor.getString(nameIndex);
            cursor.close();
        }
        if (fileName == null) fileName = uri.getLastPathSegment();
        return fileName;
    }

    private String readTextFile(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            return sb.toString().trim();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void sendAttachmentAsMessage(String content) {
        inputBox.setText(content);
        sendMessage();
    }

    private void refreshDrawerList() {
        while (drawerContent.getChildCount() > 1) drawerContent.removeViewAt(1);
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
                itemView.setPadding(20, 22, 20, 22);
                itemView.setTextColor(Color.BLACK);
                if (session.id.equals(currentChatId)) itemView.setBackgroundColor(Color.parseColor("#E3F2FD"));
                itemView.setOnClickListener(v -> { switchToChat(session); drawerLayout.closeDrawers(); });
                itemView.setOnLongClickListener(v -> {
                    String[] options = {"重命名", "删除"};
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("操作: " + session.name)
                            .setItems(options, (dialog, which) -> {
                                if (which == 0) showRenameDialog(session);
                                else if (which == 1) deleteChat(session);
                            }).show();
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
        ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前: " + currentChatName);
        loadMessagesFromDb(currentChatId);
        refreshDrawerList();
    }

    private void sendMessage() {
        String text = inputBox.getText().toString().trim();
        if (text.isEmpty() && pendingImageBase64.isEmpty()) return;
        if (text.isEmpty() && !pendingImageBase64.isEmpty()) text = "请描述这张图片";
        lastUserMessage = text;

        // 保存图片数据到局部变量，然后清空预览
        String localImageBase64 = pendingImageBase64;
        boolean hasImage = !localImageBase64.isEmpty();
        if (hasImage) {
            clearImagePreview();
        }

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
            ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前: " + currentChatName);
            refreshDrawerList();
        }

        if (hasImage) {
            displayMessage("user", "[图片] " + text);
            saveMessageToDb(currentChatId, "user", "[图片] " + text);
        } else {
            displayMessage("user", text);
            saveMessageToDb(currentChatId, "user", text);
        }
        inputBox.setText("");
        inputBox.setHint("输入消息...");

        String apiKey = cachedApiKey;
        String apiUrl = cachedApiUrl;
        String model = cachedModel;
        if (apiKey.isEmpty()) {
            displayMessage("system", "⚠️ 请先在设置中填写 API Key");
            saveMessageToDb(currentChatId, "system", "⚠️ 请先在设置中填写 API Key");
            return;
        }
        if (apiUrl.isEmpty()) apiUrl = "https://www.wintoken.dev";
        if (model.isEmpty()) {
            displayMessage("system", "⚠️ 请先在设置中选择模型");
            saveMessageToDb(currentChatId, "system", "⚠️ 请先在设置中选择模型");
            return;
        }
        if (!apiUrl.endsWith("/chat/completions")) {
            if (apiUrl.endsWith("/v1")) apiUrl += "/chat/completions";
            else if (!apiUrl.endsWith("/")) apiUrl += "/v1/chat/completions";
            else apiUrl += "v1/chat/completions";
        }

        displayMessage("system", "🤔 正在思考...");
        List<Message> history = getRecentHistory(currentChatId, MAX_HISTORY_ROUNDS);

        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            JSONArray messages = new JSONArray();
            for (Message msg : history) {
                JSONObject histMsg = new JSONObject();
                histMsg.put("role", msg.role.equals("ai") ? "assistant" : msg.role);
                histMsg.put("content", msg.content);
                messages.put(histMsg);
            }
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            if (hasImage) {
                JSONArray contentArray = new JSONArray();
                JSONObject textPart = new JSONObject();
               

package com.aihellotalk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
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
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
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

    private LinearLayout imagePreviewBar;
    private ImageView previewImage;
    private ImageButton previewCloseBtn;
    private String pendingImageBase64 = "";

    private Spinner modelSpinner;
    private List<String> modelList = new ArrayList<>();

    private String currentChatId = "";
    private String currentChatName = "";
    private int currentChatType = ChatSession.TYPE_LOCAL; 
    private List<ChatSession> chatSessions = new ArrayList<>();
    private String lastUserMessage = "";

    private OkHttpClient httpClient;
    private Handler mainHandler;
    private DatabaseHelper dbHelper;
    private static final int MAX_HISTORY_ROUNDS = 50;

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
        topBar.setPadding(16, 48, 16, 16);
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

        TextView modelLabel = new TextView(this);
        modelLabel.setText(cachedModel.isEmpty() ? "未选择" : cachedModel);
        modelLabel.setTextSize(12f);
        modelLabel.setTextColor(Color.parseColor("#666666"));
        modelLabel.setPadding(0, 0, 8, 0);
        modelLabel.setGravity(Gravity.CENTER_VERTICAL);
        modelLabel.setTag("modelLabel");

        Button rightMenuBtn = new Button(this);
        rightMenuBtn.setText("⋮");
        rightMenuBtn.setTextSize(24f);
        rightMenuBtn.setBackgroundColor(Color.TRANSPARENT);
        rightMenuBtn.setOnClickListener(this::showPopupMenu);

        topBar.addView(leftMenuBtn);
        topBar.addView(title);
        topBar.addView(modelLabel);
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
        messageScrollView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        messageScrollView.setPadding(16, 8, 16, 8);
        messageContainer = new LinearLayout(this);
        messageContainer.setOrientation(LinearLayout.VERTICAL);
        messageScrollView.addView(messageContainer);
        mainContent.addView(messageScrollView);

        // 图片预览条
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
        bottomBar.setPadding(8, 6, 8, 6);
        bottomBar.setBackgroundColor(Color.parseColor("#F0F0F0"));

        attachBtn = new Button(this);
        attachBtn.setText("+");
        attachBtn.setTextSize(24f);
        attachBtn.setBackgroundColor(Color.TRANSPARENT);
        attachBtn.setMinWidth(dpToPx(42));
        attachBtn.setMinimumHeight(dpToPx(42));
        attachBtn.setOnClickListener(v -> showAttachMenu());
        bottomBar.addView(attachBtn);

        modelList = loadModelList();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelList);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner = new Spinner(this);
        modelSpinner.setAdapter(adapter);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.3f);
        spinnerParams.setMarginEnd(dpToPx(4));
        modelSpinner.setLayoutParams(spinnerParams);
        modelSpinner.setMinimumHeight(dpToPx(42));

        String currentModel = prefs.getString("model", "");
        if (!currentModel.isEmpty() && modelList.contains(currentModel)) {
            modelSpinner.setSelection(modelList.indexOf(currentModel));
        }
        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = modelList.get(position);
                if (!selected.isEmpty()) {
                    cachedModel = selected;
                    prefs.edit().putString("model", selected).apply();
                    TextView ml = (TextView) drawerLayout.findViewWithTag("modelLabel");
                    if (ml != null) ml.setText(selected);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        bottomBar.addView(modelSpinner);

        inputBox = new EditText(this);
        inputBox.setHint("输入消息...");
        inputBox.setBackgroundColor(Color.WHITE);
        inputBox.setPadding(12, 8, 12, 8);
        inputBox.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        inputBox.setMinimumHeight(dpToPx(42));
        bottomBar.addView(inputBox);

        sendBtn = new Button(this);
        sendBtn.setText("发送");
        sendBtn.setTextSize(14f);
        sendBtn.setMinWidth(dpToPx(56));
        sendBtn.setMinimumHeight(dpToPx(42));
        sendBtn.setOnClickListener(v -> sendMessage());
        bottomBar.addView(sendBtn);

        mainContent.addView(bottomBar);
        drawerLayout.addView(mainContent);

        // 侧滑菜单
        drawerContent = new LinearLayout(this);
        drawerContent.setOrientation(LinearLayout.VERTICAL);
        drawerContent.setPadding(20, 90, 20, 20);
        drawerContent.setBackgroundColor(Color.parseColor("#FAFAFA"));
        DrawerLayout.LayoutParams dlp = new DrawerLayout.LayoutParams(dpToPx(280), ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.gravity = Gravity.LEFT;
        drawerContent.setLayoutParams(dlp);

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("对话列表");
        drawerTitle.setTextSize(18f);
        drawerTitle.setTextColor(Color.BLACK);
        drawerTitle.setPadding(0, 0, 0, 20);
        drawerContent.addView(drawerTitle);

        refreshDrawerList();
        drawerLayout.addView(drawerContent);
        setContentView(drawerLayout);
    }

    // ──────────────────────────────────────
    // 新增：Root 权限命令执行方法
    // ──────────────────────────────────────
    private String runRoot(String cmd) {
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

    // ──────────────────────────────────────
    // 新增：读取 HelloTalk 好友列表
    // ──────────────────────────────────────
    private List<ChatSession> loadHTFriendsRoot() {
        List<ChatSession> list = new ArrayList<>();
        try {
            String jsonStr = runRoot("cat /data/data/com.hellotalk/files/htai_friends.json");
            if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                JSONObject friends = new JSONObject(jsonStr);
                JSONArray names = friends.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String id = names.getString(i);
                        JSONObject info = friends.getJSONObject(id);
                        String name = info.optString("name", id);
                        list.add(new ChatSession(id, name, ChatSession.TYPE_HT));
                    }
                }
            }
        } catch (Exception e) {}
        return list;
    }

    // ──────────────────────────────────────
    // 新增：向 HelloTalk 底层追加调教指令
    // ──────────────────────────────────────
    private void appendHTHistoryRoot(String chatId, String role, String content) {
        try {
            String path = "/data/data/com.hellotalk/files/htai_hist_" + chatId + ".json";
            String jsonStr = runRoot("cat " + path);
            JSONArray history;
            if (jsonStr != null && !jsonStr.trim().isEmpty() && jsonStr.startsWith("[")) {
                history = new JSONArray(jsonStr);
            } else {
                history = new JSONArray();
            }

            JSONObject entry = new JSONObject();
            entry.put("role", role);
            entry.put("content", content);
            history.put(entry);

            // 利用临时文件规避 JSON 中的特殊符号在 Shell 转义时的报错
            File tempFile = new File(getCacheDir(), "htai_temp.json");
            BufferedWriter w = new BufferedWriter(new java.io.FileWriter(tempFile));
            w.write(history.toString());
            w.close();

            runRoot("cp " + tempFile.getAbsolutePath() + " " + path);
            runRoot("chmod 666 " + path);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<String> loadModelList() {
        List<String> list = new ArrayList<>();
        String saved = prefs.getString("model_list", "");
        if (!saved.isEmpty()) {
            for (String s : saved.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) list.add(t);
            }
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
        TextView nv = (TextView) imagePreviewBar.findViewWithTag("previewName");
        if (nv != null) nv.setText("已选择: " + fileName);
        imagePreviewBar.setVisibility(View.VISIBLE);
    }

    private String imageUriToBase64(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            Bitmap orig = BitmapFactory.decodeStream(is);
            is.close();
            if (orig == null) return null;
            Bitmap thumb = Bitmap.createScaledBitmap(orig, dpToPx(112), dpToPx(112), true);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            orig.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            byte[] bytes = baos.toByteArray();
            orig.recycle();
            showImagePreview(thumb, getFileName(uri));
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
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
    }

    private String readConfig(String key) {
        String content = runRoot("cat /data/local/tmp/htai_config.txt");
        if (content == null) return "";
        for (String l : content.split("\n")) {
            if (l.trim().startsWith(key + "=")) {
                return l.trim().substring(key.length() + 1).trim();
            }
        }
        return "";
    }

    private void loadChatSessions() {
        chatSessions.clear();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, name FROM chats ORDER BY updated_at DESC", null);
        while (c.moveToNext()) {
            chatSessions.add(new ChatSession(c.getString(0), c.getString(1), ChatSession.TYPE_LOCAL));
        }
        c.close();
        db.close();
    }

    private void loadMessagesFromDb(String chatId) {
        messageContainer.removeAllViews();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT role, content FROM messages WHERE chat_id=? ORDER BY timestamp ASC", new String[]{chatId});
        while (c.moveToNext()) {
            displayMessage(c.getString(0), c.getString(1));
        }
        c.close();
        db.close();
    }

    private void loadHTMessagesRoot(String chatId) {
        messageContainer.removeAllViews();
        try {
            String jsonStr = runRoot("cat /data/data/com.hellotalk/files/htai_hist_" + chatId + ".json");
            if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                JSONArray history = new JSONArray(jsonStr);
                for (int i = 0; i < history.length(); i++) {
                    JSONObject obj = history.getJSONObject(i);
                    String role = obj.optString("role", "");
                    String content = obj.optString("content", "");
                    
                    if ("user".equals(role)) {
                        displayMessage("user", content);
                    } else if ("assistant".equals(role)) {
                        displayMessage("ai", content);
                    } else {
                        displayMessage("system", content);
                    }
                }
            }
        } catch (Exception e) {
            displayMessage("system", "⚠️ 读取该好友记录失败");
        }
    }

    private void saveMessageToDb(String chatId, String role, String content) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("chat_id", chatId);
        v.put("role", role);
        v.put("content", content);
        v.put("timestamp", System.currentTimeMillis());
        db.insert("messages", null, v);
        db.close();
    }

    private List<Message> getRecentHistory(String chatId, int maxRounds) {
        List<Message> all = new ArrayList<>();
        List<Message> hist = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT role, content FROM messages WHERE chat_id=? ORDER BY timestamp ASC", new String[]{chatId});
        while (c.moveToNext()) {
            all.add(new Message(c.getString(0), c.getString(1)));
        }
        c.close();
        db.close();
        int start = Math.max(0, all.size() - maxRounds * 2);
        for (int i = start; i < all.size(); i++) hist.add(all.get(i));
        return hist;
    }

    private void showAttachMenu() {
        if (currentChatType == ChatSession.TYPE_HT) {
            Toast.makeText(this, "⚠️ 翻译遥控模式仅支持发送文本指令", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择附件")
                .setItems(new String[]{"🖼️ 相册", "📎 文件"}, (d, w) -> {
                    if (w == 0) pickImage();
                    else if (w == 1) pickFile();
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
            String b64 = imageUriToBase64(uri);
            if (b64 != null) {
                pendingImageBase64 = b64;
                inputBox.setHint("已选择图片: " + getFileName(uri) + "，输入描述后发送");
                Toast.makeText(this, "已选择图片", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_CODE_PICK_FILE) {
            String fc = readTextFile(uri);
            if (fc != null) {
                sendAttachmentAsMessage("用户上传了文件：" + getFileName(uri) + "\n内容：\n" + fc);
            } else {
                Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String getFileName(Uri uri) {
        String name = null;
        Cursor c = getContentResolver().query(uri, null, null, null, null);
        if (c != null && c.moveToFirst()) {
            int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (idx >= 0) name = c.getString(idx);
            c.close();
        }
        if (name == null) name = uri.getLastPathSegment();
        return name;
    }

    private String readTextFile(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append("\n");
            r.close();
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

    // ──────────────────────────────────────
    // 修改：双轨制侧滑菜单界面
    // ──────────────────────────────────────
    private void refreshDrawerList() {
        while (drawerContent.getChildCount() > 1) drawerContent.removeViewAt(1);

        // --- 1. HelloTalk 翻译遥控区 ---
        TextView htLabel = new TextView(this);
        htLabel.setText("【 🌐 HelloTalk 翻译遥控 】");
        htLabel.setTextSize(14f);
        htLabel.setTextColor(Color.parseColor("#0056b3"));
        htLabel.setPadding(0, 30, 0, 10);
        drawerContent.addView(htLabel);

        List<ChatSession> htFriends = loadHTFriendsRoot();
        if (htFriends.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("暂无检测到的好友，请先在 HelloTalk 打开聊天页");
            hint.setTextSize(13f);
            hint.setTextColor(Color.GRAY);
            hint.setPadding(20, 10, 0, 20);
            drawerContent.addView(hint);
        } else {
            for (ChatSession s : htFriends) {
                drawerContent.addView(createDrawerItem(s, "🌐 "));
            }
        }

        // --- 2. 本地闲聊区 ---
        TextView locLabel = new TextView(this);
        locLabel.setText("【 💬 本地 AI 闲聊 】");
        locLabel.setTextSize(14f);
        locLabel.setTextColor(Color.parseColor("#28a745"));
        locLabel.setPadding(0, 30, 0, 10);
        drawerContent.addView(locLabel);

        if (chatSessions.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("发送第一条消息后自动创建");
            hint.setTextSize(13f);
            hint.setTextColor(Color.GRAY);
            hint.setPadding(20, 10, 0, 20);
            drawerContent.addView(hint);
        } else {
            for (ChatSession s : chatSessions) {
                drawerContent.addView(createDrawerItem(s, "💬 "));
            }
        }
    }

    private TextView createDrawerItem(ChatSession s, String prefix) {
        TextView tv = new TextView(this);
        tv.setText(prefix + s.name);
        tv.setTextSize(16f);
        tv.setPadding(20, 22, 20, 22);
        tv.setTextColor(Color.BLACK);
        if (s.id.equals(currentChatId)) tv.setBackgroundColor(Color.parseColor("#E3F2FD"));
        tv.setOnClickListener(v -> { switchToChat(s); drawerLayout.closeDrawers(); });

        if (s.type == ChatSession.TYPE_LOCAL) {
            tv.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("操作: " + s.name)
                        .setItems(new String[]{"重命名", "删除"}, (d, w) -> {
                            if (w == 0) showRenameDialog(s);
                            else if (w == 1) deleteChat(s);
                        })
                        .show();
                return true;
            });
        } else {
            tv.setOnLongClickListener(v -> {
                Toast.makeText(this, "⚠️ HelloTalk 内部记忆不支持在此删除", Toast.LENGTH_SHORT).show();
                return true;
            });
        }
        return tv;
    }

    private void deleteChat(ChatSession s) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete("messages", "chat_id=?", new String[]{s.id});
        db.delete("chats", "id=?", new String[]{s.id});
        db.close();
        chatSessions.remove(s);
        if (currentChatId.equals(s.id)) {
            currentChatId = "";
            currentChatName = "";
            ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前: 暂无对话");
            messageContainer.removeAllViews();
        }
        refreshDrawerList();
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
    }

    private void showRenameDialog(ChatSession s) {
        EditText et = new EditText(this);
        et.setText(s.name);
        et.setSelection(s.name.length());
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String nn = et.getText().toString().trim();
                    if (!nn.isEmpty()) {
                        SQLiteDatabase db = dbHelper.getWritableDatabase();
                        ContentValues cv = new ContentValues();
                        cv.put("name", nn);
                        db.update("chats", cv, "id=?", new String[]{s.id});
                        db.close();
                        s.name = nn;
                        refreshDrawerList();
                        if (currentChatId.equals(s.id)) {
                            String prefix = (currentChatType == ChatSession.TYPE_HT) ? "🌐 " : "💬 ";
                            currentChatName = nn;
                            ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前: " + prefix + nn);
                        }
                        Toast.makeText(this, "已重命名", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPopupMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenu().add(0, 1, 0, "💬 开启新闲聊对话");
        popup.getMenu().add(0, 2, 0, "⚙️ 设置/API配置");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                currentChatId = "";
                currentChatName = "";
                currentChatType = ChatSession.TYPE_LOCAL;
                ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前: 暂无对话");
                messageContainer.removeAllViews();
                inputBox.setHint("输入消息...");
                Toast.makeText(this, "已开启本地新对话", Toast.LENGTH_SHORT).show();
                return true;
            } else if (item.getItemId() == 2) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });
        popup.show();
    }

    // ──────────────────────────────────────
    // 修改：点击不同频道的加载逻辑
    // ──────────────────────────────────────
    private void switchToChat(ChatSession session) {
        currentChatId = session.id;
        currentChatName = session.name;
        currentChatType = session.type;
        String prefix = (session.type == ChatSession.TYPE_HT) ? "🌐 " : "💬 ";
        ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前: " + prefix + currentChatName);
        
        if (session.type == ChatSession.TYPE_HT) {
            loadHTMessagesRoot(currentChatId);
            inputBox.setHint("输入对 AI 的翻译调教指令...");
        } else {
            loadMessagesFromDb(currentChatId);
            inputBox.setHint("输入消息...");
        }
        refreshDrawerList();
    }

    // ──────────────────────────────────────
    // 修改：分发发送逻辑
    // ──────────────────────────────────────
    private void sendMessage() {
        String text = inputBox.getText().toString().trim();
        if (text.isEmpty() && pendingImageBase64.isEmpty()) return;
        if (text.isEmpty() && !pendingImageBase64.isEmpty()) text = "请描述这张图片";
        lastUserMessage = text;

        String localImageBase64 = pendingImageBase64;
        boolean hasImage = !localImageBase64.isEmpty();
        if (hasImage) clearImagePreview();

        // 1. 如果当前是 HelloTalk 频道
        if (currentChatType == ChatSession.TYPE_HT) {
            if (hasImage) {
                Toast.makeText(this, "⚠️ 遥控模式只接收文本指令", Toast.LENGTH_SHORT).show();
                return;
            }
            displayMessage("user", text);
            appendHTHistoryRoot(currentChatId, "user", text);
            displayMessage("system", "✅ 已将该指令静默写入底层记忆！\n切回 HelloTalk 聊天并点击“译”即可按照此指令执行。");
            inputBox.setText("");
            return;
        }

        // 2. 以下全是原本的本地闲聊频道代码
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
            ChatSession ns = new ChatSession(chatId, chatName, ChatSession.TYPE_LOCAL);
            chatSessions.add(ns);
            currentChatId = chatId;
            currentChatName = chatName;
            ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前: 💬 " + currentChatName);
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
                JSONObject hm = new JSONObject();
                hm.put("role", msg.role.equals("ai") ? "assistant" : msg.role);
                hm.put("content", msg.content);
                messages.put(hm);
            }
            JSONObject um = new JSONObject();
            um.put("role", "user");
            if (hasImage) {
                JSONArray ca = new JSONArray();
                JSONObject tp = new JSONObject();
                tp.put("type", "text");
                tp.put("text", text);
                ca.put(tp);
                JSONObject ip = new JSONObject();
                ip.put("type", "image_url");
                JSONObject iu = new JSONObject();
                iu.put("url", "data:image/jpeg;base64," + localImageBase64);
                ip.put("image_url", iu);
                ca.put(ip);
                um.put("content", ca);
            } else {
                um.put("content", text);
            }
            messages.put(um);
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
                    String body = response.body().string();
                    mainHandler.post(() -> {
                        removeLastSystemMessage();
                        if (response.isSuccessful()) {
                            try {
                                JSONObject j = new JSONObject(body);
                                String reply = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                                displayMessage("ai", reply);
                                saveMessageToDb(currentChatId, "ai", reply);
                            } catch (Exception ex) {
                                displayMessage("system", "❌ 解析响应失败: " + ex.getMessage());
                                saveMessageToDb(currentChatId, "system", "❌ 解析响应失败: " + ex.getMessage());
                            }
                        } else {
                            displayMessage("system", "❌ 服务器错误 " + response.code() + ": " + body);
                            saveMessageToDb(currentChatId, "system", "❌ 服务器错误 " + response.code() + ": " + body);
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
        if (currentChatType == ChatSession.TYPE_HT) {
            Toast.makeText(this, "⚠️ 遥控模式不支持重新生成，请在 HelloTalk 中点重新翻译", Toast.LENGTH_SHORT).show();
            return;
        }
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
                    TextView b = (TextView) row.getChildAt(0);
                    String t = b.getText().toString();
                    if (!t.contains("正在思考") && !t.contains("⚠️") && !t.contains("❌") && !t.contains("✅")) {
                        if (b.getCurrentTextColor() == Color.parseColor("#E8E8E8") || b.getBackground() != null) {
                            messageContainer.removeViewAt(i);
                            return;
                        }
                    }
                }
            }
        }
    }

    private void displayMessage(String role, String content) {
        String filtered = content.replaceAll("[*\\-#：；—]", "");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 8, 0, 8);

        if ("ai".equals(role)) {
            LinearLayout wrapper = new LinearLayout(this);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView bubble = new TextView(this);
            bubble.setText(filtered);
            bubble.setTextSize(15f);
            bubble.setPadding(16, 12, 16, 12);
            bubble.setLineSpacing(4f, 1f);
            bubble.setBackgroundColor(Color.parseColor("#E8E8E8"));
            bubble.setTextIsSelectable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.width = (int)(getResources().getDisplayMetrics().widthPixels * 0.7);
            bubble.setLayoutParams(lp);
            wrapper.addView(bubble);
            row.addView(wrapper);
            Button rb = new Button(this);
            rb.setText("🔄");
            rb.setTextSize(18f);
            rb.setBackgroundColor(Color.TRANSPARENT);
            rb.setOnClickListener(v -> regenerateAnswer());
            if (currentChatType == ChatSession.TYPE_HT) rb.setVisibility(View.GONE);
            row.addView(rb);
            row.setGravity(Gravity.START);
        } else if ("user".equals(role)) {
            TextView bubble = new TextView(this);
            bubble.setText(filtered);
            bubble.setTextSize(15f);
            bubble.setPadding(16, 12, 16, 12);
            bubble.setLineSpacing(4f, 1f);
            bubble.setBackgroundColor(Color.parseColor("#DCF8C6"));
            bubble.setTextIsSelectable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.width = (int)(getResources().getDisplayMetrics().widthPixels * 0.7);
            bubble.setLayoutParams(lp);
            LinearLayout spacer = new LinearLayout(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(spacer);
            row.addView(bubble);
            row.setGravity(Gravity.END);
        } else {
            TextView bubble = new TextView(this);
            bubble.setText(filtered);
            bubble.setTextSize(13f);
            bubble.setPadding(16, 12, 16, 12);
            bubble.setLineSpacing(4f, 1f);
            bubble.setBackgroundColor(Color.parseColor("#FFF3CD"));
            bubble.setTextIsSelectable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.width = (int)(getResources().getDisplayMetrics().widthPixels * 0.75);
            bubble.setLayoutParams(lp);
            row.addView(bubble);
            row.setGravity(Gravity.CENTER);
        }
        messageContainer.addView(row);
        messageScrollView.post(() -> messageScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void removeLastSystemMessage() {
        if (messageContainer.getChildCount() == 0) return;
        View last = messageContainer.getChildAt(messageContainer.getChildCount() - 1);
        if (last instanceof LinearLayout) {
            LinearLayout r = (LinearLayout) last;
            if (r.getChildCount() > 0 && r.getChildAt(0) instanceof TextView) {
                String t = ((TextView) r.getChildAt(0)).getText().toString();
                if (t.contains("正在思考") || t.contains("⚠️") || t.contains("❌")) {
                    messageContainer.removeViewAt(messageContainer.getChildCount() - 1);
                }
            }
        }
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    static class ChatSession {
        public static final int TYPE_LOCAL = 0;
        public static final int TYPE_HT = 1;
        
        String id;
        String name;
        int type;
        
        ChatSession(String id, String name, int type) {
            this.id = id;
            this.name = name;
            this.type = type;
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
        DatabaseHelper(android.content.Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }
        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE chats (id TEXT PRIMARY KEY, name TEXT NOT NULL, updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, timestamp INTEGER NOT NULL, FOREIGN KEY (chat_id) REFERENCES chats(id))");
        }
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS messages");
            db.execSQL("DROP TABLE IF EXISTS chats");
            onCreate(db);
        }
    }
}

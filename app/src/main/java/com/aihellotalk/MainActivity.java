package com.aihellotalk;

import android.app.Activity;
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
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private DrawerLayout drawerLayout;
    private LinearLayout messageContainer;
    private ScrollView messageScrollView;
    private EditText inputBox;
    private Button sendBtn;
    private LinearLayout drawerContent;

    private String currentChatId = "";
    private String currentChatName = "";
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());

        drawerLayout = new DrawerLayout(this);
        LinearLayout mainContent = new LinearLayout(this);
        mainContent.setOrientation(LinearLayout.VERTICAL);
        mainContent.setBackgroundColor(Color.WHITE);

        // ──────────────────────────────────────
        // 顶部栏
        // ──────────────────────────────────────
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
        title.setText("HT 翻译遥控器");
        title.setTextSize(18f);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        topBar.addView(leftMenuBtn);
        topBar.addView(title);
        // 为了对称占位
        Button placeholderBtn = new Button(this);
        placeholderBtn.setText("  ");
        placeholderBtn.setBackgroundColor(Color.TRANSPARENT);
        topBar.addView(placeholderBtn);
        mainContent.addView(topBar);

        // 当前对话标题
        TextView chatTitle = new TextView(this);
        chatTitle.setText("当前: 未选择好友");
        chatTitle.setTag("chatTitle");
        chatTitle.setPadding(16, 12, 16, 12);
        chatTitle.setTextSize(14f);
        chatTitle.setTextColor(Color.GRAY);
        chatTitle.setBackgroundColor(Color.parseColor("#E9ECEF"));
        mainContent.addView(chatTitle);

        // ──────────────────────────────────────
        // 消息滚动区
        // ──────────────────────────────────────
        messageScrollView = new ScrollView(this);
        messageScrollView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        messageScrollView.setPadding(16, 8, 16, 8);
        messageContainer = new LinearLayout(this);
        messageContainer.setOrientation(LinearLayout.VERTICAL);
        messageScrollView.addView(messageContainer);
        mainContent.addView(messageScrollView);

        // ──────────────────────────────────────
        // 极简底部输入栏
        // ──────────────────────────────────────
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(16, 12, 16, 12);
        bottomBar.setBackgroundColor(Color.parseColor("#F0F0F0"));

        inputBox = new EditText(this);
        inputBox.setHint("输入对 AI 的翻译调教指令...");
        inputBox.setBackgroundColor(Color.WHITE);
        inputBox.setPadding(24, 16, 24, 16);
        inputBox.setTextSize(15f);
        inputBox.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        inputBox.setMinimumHeight(dpToPx(48));
        bottomBar.addView(inputBox);

        sendBtn = new Button(this);
        sendBtn.setText("注入指令");
        sendBtn.setTextSize(14f);
        sendBtn.setTextColor(Color.WHITE);
        sendBtn.setBackgroundColor(Color.parseColor("#007BFF"));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(16, 0, 0, 0);
        sendBtn.setLayoutParams(btnParams);
        sendBtn.setMinimumHeight(dpToPx(48));
        sendBtn.setOnClickListener(v -> sendMessage());
        bottomBar.addView(sendBtn);

        mainContent.addView(bottomBar);
        drawerLayout.addView(mainContent);

        // ──────────────────────────────────────
        // 侧滑菜单 (仅展示 HelloTalk 好友)
        // ──────────────────────────────────────
        drawerContent = new LinearLayout(this);
        drawerContent.setOrientation(LinearLayout.VERTICAL);
        drawerContent.setPadding(20, 90, 20, 20);
        drawerContent.setBackgroundColor(Color.parseColor("#FAFAFA"));
        DrawerLayout.LayoutParams dlp = new DrawerLayout.LayoutParams(dpToPx(280), ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.gravity = Gravity.LEFT;
        drawerContent.setLayoutParams(dlp);

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("HT 遥控好友列表");
        drawerTitle.setTextSize(18f);
        drawerTitle.setTextColor(Color.BLACK);
        drawerTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        drawerTitle.setPadding(10, 0, 0, 30);
        drawerContent.addView(drawerTitle);

        refreshDrawerList();
        drawerLayout.addView(drawerContent);
        setContentView(drawerLayout);
    }

    // ──────────────────────────────────────
    // Root 权限 Shell 执行
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
    // 从底层读取 HelloTalk 好友
    // ──────────────────────────────────────
    private void refreshDrawerList() {
        while (drawerContent.getChildCount() > 1) drawerContent.removeViewAt(1);

        List<ChatSession> htFriends = new ArrayList<>();
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
                        htFriends.add(new ChatSession(id, name));
                    }
                }
            }
        } catch (Exception ignored) {}

        if (htFriends.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("未检测到好友。\n请先在 HelloTalk 中打开任意聊天界面。");
            hint.setTextSize(14f);
            hint.setTextColor(Color.GRAY);
            hint.setPadding(10, 20, 0, 20);
            drawerContent.addView(hint);
        } else {
            for (ChatSession s : htFriends) {
                TextView tv = new TextView(this);
                tv.setText("👤 " + s.name);
                tv.setTextSize(16f);
                tv.setPadding(20, 26, 20, 26);
                tv.setTextColor(Color.parseColor("#333333"));
                if (s.id.equals(currentChatId)) tv.setBackgroundColor(Color.parseColor("#E3F2FD"));
                
                tv.setOnClickListener(v -> {
                    currentChatId = s.id;
                    currentChatName = s.name;
                    ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前遥控: " + currentChatName);
                    loadHTMessagesRoot(currentChatId);
                    drawerLayout.closeDrawers();
                    refreshDrawerList(); // 更新高亮状态
                });
                drawerContent.addView(tv);
            }
        }
    }

    // ──────────────────────────────────────
    // 从底层读取该好友的翻译上下文
    // ──────────────────────────────────────
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
                
                // 滚动到底部
                messageScrollView.postDelayed(() -> messageScrollView.fullScroll(View.FOCUS_DOWN), 100);
            } else {
                displayMessage("system", "暂无与该好友的翻译记录");
            }
        } catch (Exception e) {
            displayMessage("system", "⚠️ 读取该好友记录失败");
        }
    }

    // ──────────────────────────────────────
    // 将调教指令直接暴力写入底层 JSON
    // ──────────────────────────────────────
    private void sendMessage() {
        if (currentChatId.isEmpty()) {
            Toast.makeText(this, "请先在左侧选择要调教的好友", Toast.LENGTH_SHORT).show();
            drawerLayout.openDrawer(Gravity.LEFT);
            return;
        }

        String text = inputBox.getText().toString().trim();
        if (text.isEmpty()) return;

        // 立即在界面上显示该指令
        displayMessage("user", "[调教指令] " + text);
        inputBox.setText("");

        new Thread(() -> {
            try {
                String path = "/data/data/com.hellotalk/files/htai_hist_" + currentChatId + ".json";
                String jsonStr = runRoot("cat " + path);
                JSONArray history;
                if (jsonStr != null && !jsonStr.trim().isEmpty() && jsonStr.startsWith("[")) {
                    history = new JSONArray(jsonStr);
                } else {
                    history = new JSONArray();
                }

                JSONObject entry = new JSONObject();
                entry.put("role", "user"); // 作为 user 提示词强行插入
                entry.put("content", text);
                history.put(entry);

                // 利用缓存目录中转，防止转义字符直接通过 Shell 写入报错
                File tempFile = new File(getCacheDir(), "htai_temp.json");
                BufferedWriter w = new BufferedWriter(new java.io.FileWriter(tempFile));
                w.write(history.toString());
                w.close();

                runRoot("cp " + tempFile.getAbsolutePath() + " " + path);
                runRoot("chmod 666 " + path);
                
                mainHandler.post(() -> {
                    displayMessage("system", "✅ 指令已静默注入！\n切回 HelloTalk 再次点击“译”按钮生效。");
                    messageScrollView.post(() -> messageScrollView.fullScroll(View.FOCUS_DOWN));
                });
            } catch (Exception e) {
                mainHandler.post(() -> displayMessage("system", "❌ 注入失败: " + e.getMessage()));
            }
        }).start();
    }

    // ──────────────────────────────────────
    // 渲染聊天气泡
    // ──────────────────────────────────────
    private void displayMessage(String role, String content) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 12, 0, 12);

        TextView bubble = new TextView(this);
        bubble.setText(content);
        bubble.setTextSize(15f);
        bubble.setPadding(24, 18, 24, 18);
        bubble.setLineSpacing(6f, 1f);
        bubble.setTextIsSelectable(true);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.width = (int)(getResources().getDisplayMetrics().widthPixels * 0.75);

        if ("ai".equals(role)) {
            bubble.setBackgroundColor(Color.parseColor("#E8E8E8"));
            bubble.setTextColor(Color.parseColor("#212529"));
            bubble.setLayoutParams(lp);
            row.addView(bubble);
            row.setGravity(Gravity.START);
        } else if ("user".equals(role)) {
            bubble.setBackgroundColor(Color.parseColor("#DCF8C6"));
            bubble.setTextColor(Color.parseColor("#155724"));
            bubble.setLayoutParams(lp);
            
            LinearLayout spacer = new LinearLayout(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(spacer);
            row.addView(bubble);
            row.setGravity(Gravity.END);
        } else {
            // System 提示消息
            bubble.setBackgroundColor(Color.parseColor("#FFF3CD"));
            bubble.setTextColor(Color.parseColor("#856404"));
            bubble.setTextSize(13f);
            lp.width = (int)(getResources().getDisplayMetrics().widthPixels * 0.85);
            bubble.setLayoutParams(lp);
            row.addView(bubble);
            row.setGravity(Gravity.CENTER);
        }
        messageContainer.addView(row);
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
}

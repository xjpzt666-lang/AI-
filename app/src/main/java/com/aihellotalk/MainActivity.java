package com.aihellotalk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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

    private String currentChatName = "";
    private List<ChatSession> chatSessions = new ArrayList<>();
    private boolean hasSentFirstMessage = false;

    private OkHttpClient httpClient;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        mainHandler = new Handler(Looper.getMainLooper());

        // 不再调用 initChatSessions，列表初始为空
        chatSessions = new ArrayList<>();

        drawerLayout = new DrawerLayout(this);

        // ── 主聊天区 ──
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

        // 当前对话提示条（初始为空）
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

        // ── 底部输入区 ──
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

        // ── 侧滑菜单区 ──
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
        drawerTitle.setPadding(0,0,0,20);
        drawerContent.addView(drawerTitle);

        // 初始显示空列表提示
        TextView emptyHint = new TextView(this);
        emptyHint.setText("发送第一条消息后自动创建");
        emptyHint.setTextSize(14f);
        emptyHint.setTextColor(Color.GRAY);
        emptyHint.setPadding(0, 20, 0, 20);
        emptyHint.setTag("emptyHint");
        drawerContent.addView(emptyHint);

        drawerLayout.addView(drawerContent);
        setContentView(drawerLayout);
    }

    // 加号菜单
    private void showAttachMenu() {
        String[] options = {"📷 拍照", "🖼️ 相册", "📎 文件"};
        new AlertDialog.Builder(this)
                .setTitle("选择附件")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        Toast.makeText(this, "拍照功能待实现", Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        Toast.makeText(this, "相册功能待实现", Toast.LENGTH_SHORT).show();
                    } else if (which == 2) {
                        Toast.makeText(this, "文件功能待实现", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // 刷新侧滑菜单列表
    private void refreshDrawerList() {
        // 移除所有子 view（除了标题）
        while (drawerContent.getChildCount() > 1) {
            drawerContent.removeViewAt(1);
        }

        if (chatSessions.isEmpty()) {
            TextView emptyHint = new TextView(this);
            emptyHint.setText("发送第一条消息后自动创建");
            emptyHint.setTextSize(14f);
            emptyHint.setTextColor(Color.GRAY);
            emptyHint.setPadding(0, 20, 0, 20);
            drawerContent.addView(emptyHint);
        } else {
            for (ChatSession session : chatSessions) {
                TextView itemView = new TextView(this);
                itemView.setText("💬 " + session.name);
                itemView.setTextSize(16f);
                itemView.setPadding(20, 20, 20, 20);
                itemView.setTextColor(Color.BLACK);
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
                                else if (which == 1) {
                                    chatSessions.remove(session);
                                    refreshDrawerList();
                                    Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                                    // 如果删除的是当前对话，重置
                                    if (currentChatName.equals(session.name)) {
                                        currentChatName = "";
                                        ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前: 暂无对话");
                                        messageContainer.removeAllViews();
                                    }
                                }
                            })
                            .show();
                    return true;
                });
                drawerContent.addView(itemView);
            }
        }
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
                        session.name = newName;
                        refreshDrawerList();
                        Toast.makeText(MainActivity.this, "已重命名为: " + newName, Toast.LENGTH_SHORT).show();
                        if (currentChatName.equals(session.name)) {
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
                messageContainer.removeAllViews();
                currentChatName = "";
                hasSentFirstMessage = false;
                ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前: 暂无对话");
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
        currentChatName = session.name;
        TextView chatTitle = (TextView) drawerLayout.findViewWithTag("chatTitle");
        if (chatTitle != null) chatTitle.setText("当前: " + currentChatName);
        messageContainer.removeAllViews();
        // 这里可以恢复该对话的历史消息（后续实现）
    }

    // ── 发送消息 ──
    private void sendMessage() {
        String text = inputBox.getText().toString().trim();
        if (text.isEmpty()) return;

        // 如果是第一条消息，自动创建新对话
        if (!hasSentFirstMessage) {
            String chatName = text.length() > 10 ? text.substring(0, 10) + "..." : text;
            ChatSession newSession = new ChatSession(chatName);
            chatSessions.add(newSession);
            currentChatName = chatName;
            hasSentFirstMessage = true;
            
            TextView chatTitle = (TextView) drawerLayout.findViewWithTag("chatTitle");
            if (chatTitle != null) chatTitle.setText("当前: " + currentChatName);
            
            refreshDrawerList();
        }

        // 显示用户消息（右对齐，绿色气泡）
        addMessage("user", text);
        inputBox.setText("");

        // 从配置文件读取
        String apiKey = readConfig("api_key");
        String apiUrl = readConfig("api_url");
        String model = readConfig("model");

        if (apiKey.isEmpty()) {
            addMessage("system", "⚠️ 请先在设置中填写 API Key");
            return;
        }
        if (apiUrl.isEmpty()) {
            apiUrl = "https://api.openai.com/v1/chat/completions";
        }
        if (model.isEmpty()) {
            addMessage("system", "⚠️ 请先在设置中选择模型");
            return;
        }

        // 自动补全 URL
        if (!apiUrl.endsWith("/chat/completions")) {
            if (apiUrl.endsWith("/v1")) {
                apiUrl = apiUrl + "/chat/completions";
            } else if (!apiUrl.endsWith("/")) {
                apiUrl = apiUrl + "/v1/chat/completions";
            } else {
                apiUrl = apiUrl + "v1/chat/completions";
            }
        }

        addMessage("system", "🤔 正在思考...");

        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);

            JSONArray messages = new JSONArray();
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
                        addMessage("system", "❌ 请求失败: " + e.getMessage());
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
                                // 显示 AI 回复（左对齐，灰色气泡）
                                addMessage("ai", reply);
                            } catch (Exception e) {
                                addMessage("system", "❌ 解析响应失败: " + e.getMessage());
                            }
                        } else {
                            addMessage("system", "❌ 服务器错误 " + response.code() + ": " + responseBody);
                        }
                    });
                }
            });

        } catch (Exception e) {
            addMessage("system", "❌ 构建请求失败: " + e.getMessage());
        }
    }

    // ── 添加消息气泡 ──
    private void addMessage(String role, String content) {
        if ("system".equals(role) && content.contains("正在思考")) {
            removeLastSystemMessage();
        }

        LinearLayout msgRow = new LinearLayout(this);
        msgRow.setOrientation(LinearLayout.VERTICAL);
        msgRow.setPadding(0, 8, 0, 8);

        TextView bubble = new TextView(this);
        bubble.setText(content);
        bubble.setTextSize(15f);
        bubble.setPadding(16, 12, 16, 12);
        bubble.setLineSpacing(4f, 1f);

        if ("user".equals(role)) {
            // 用户消息：右对齐，绿色气泡
            bubble.setBackgroundColor(Color.parseColor("#DCF8C6"));
            msgRow.setGravity(Gravity.END);
        } else if ("ai".equals(role)) {
            // AI 消息：左对齐，灰色气泡
            bubble.setBackgroundColor(Color.parseColor("#E8E8E8"));
            msgRow.setGravity(Gravity.START);
        } else {
            // 系统消息：居中，黄色气泡
            bubble.setBackgroundColor(Color.parseColor("#FFF3CD"));
            msgRow.setGravity(Gravity.CENTER);
            bubble.setTextSize(13f);
        }

        // 气泡最大宽度设为屏幕宽度的 80%
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.8);
        bubble.setLayoutParams(lp);

        msgRow.addView(bubble);
        messageContainer.addView(msgRow);

        // 自动滚动到底部
        messageScrollView.post(() -> messageScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void removeLastSystemMessage() {
        if (messageContainer.getChildCount() > 0) {
            messageContainer.removeViewAt(messageContainer.getChildCount() - 1);
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
        String name;
        ChatSession(String name) { this.name = name; }
    }
}

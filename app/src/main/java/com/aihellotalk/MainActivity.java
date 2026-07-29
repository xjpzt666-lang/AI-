package com.aihellotalk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.*;

public class MainActivity extends Activity {

    private DrawerLayout drawerLayout;
    private LinearLayout messageContainer;
    private ScrollView messageScrollView;
    private EditText inputBox;
    private Spinner modelSpinner; // 改成了模型选择器
    private Button sendBtn;
    private Button attachBtn; // "+"号按钮
    private LinearLayout drawerContent;

    private String currentChatName = "自由对话";
    private List<ChatSession> chatSessions = new ArrayList<>();
    private String[] modelNames = {"gpt-3.5-turbo", "gpt-4", "gpt-4-turbo", "claude-3-opus", "claude-3-sonnet"};

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

        initChatSessions();

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

        // 当前对话提示条
        TextView chatTitle = new TextView(this);
        chatTitle.setText("当前: " + currentChatName);
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

        // ── 底部输入区（改造后）──
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(8, 8, 8, 8);
        bottomBar.setBackgroundColor(Color.parseColor("#F0F0F0"));

        // 模型选择器
        modelSpinner = new Spinner(this);
        ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelNames);
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(modelAdapter);
        modelSpinner.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // "+"号按钮（发图片/文件）
        attachBtn = new Button(this);
        attachBtn.setText("+");
        attachBtn.setTextSize(20f);
        attachBtn.setBackgroundColor(Color.TRANSPARENT);
        attachBtn.setOnClickListener(v -> showAttachMenu());

        // 输入框
        inputBox = new EditText(this);
        inputBox.setHint("输入消息...");
        inputBox.setBackgroundColor(Color.WHITE);
        inputBox.setPadding(16, 12, 16, 12);
        inputBox.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 发送按钮
        sendBtn = new Button(this);
        sendBtn.setText("发送");
        sendBtn.setOnClickListener(v -> sendMessage());

        bottomBar.addView(modelSpinner);
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
        drawerTitle.setText("长按列表项可操作");
        drawerTitle.setTextSize(14f);
        drawerTitle.setTextColor(Color.GRAY);
        drawerTitle.setPadding(0, 0, 0, 20);
        drawerContent.addView(drawerTitle);

        refreshDrawerList();

        drawerLayout.addView(drawerContent);
        setContentView(drawerLayout);
    }

    // "+"号菜单
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

    // ── 发送消息（接入真实 API）──
    private void sendMessage() {
        String text = inputBox.getText().toString().trim();
        if (text.isEmpty()) return;

        // 显示用户消息
        addMessage("user", text);
        inputBox.setText("");

        // 获取 API 配置
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        String apiKey = prefs.getString("api_key", "");
        String apiUrl = prefs.getString("api_url", "https://api.openai.com/v1/chat/completions");
        String selectedModel = modelSpinner.getSelectedItem().toString();

        if (apiKey.isEmpty()) {
            addMessage("system", "⚠️ 请先在设置中填写 API Key");
            return;
        }

        // 显示"正在思考..."
        addMessage("system", "🤔 正在思考...");

        // 构建请求体
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", selectedModel);

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

            // 异步请求
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    mainHandler.post(() -> {
                        // 移除"正在思考..."
                        removeLastSystemMessage();
                        addMessage("system", "❌ 请求失败: " + e.getMessage());
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    mainHandler.post(() -> {
                        // 移除"正在思考..."
                        removeLastSystemMessage();

                        if (response.isSuccessful()) {
                            try {
                                JSONObject json = new JSONObject(responseBody);
                                String reply = json.getJSONArray("choices")
                                        .getJSONObject(0)
                                        .getJSONObject("message")
                                        .getString("content");
                                addMessage("ai", reply);
                            } catch (Exception e) {
                                addMessage("system", "❌ 解析响应失败: " + e.getMessage());
                            }
                        } else {
                            addMessage("system", "❌ 服务器错误: " + response.code() + "\n" + responseBody);
                        }
                    });
                }
            });

        } catch (Exception e) {
            addMessage("system", "❌ 构建请求失败: " + e.getMessage());
        }
    }

    // 添加消息气泡
    private void addMessage(String role, String content) {
        // 如果是"正在思考..."，先移除旧的
        if ("system".equals(role) && content.contains("正在思考")) {
            removeLastSystemMessage();
        }

        LinearLayout msgRow = new LinearLayout(this);
        msgRow.setOrientation(LinearLayout.HORIZONTAL);
        msgRow.setPadding(0, 4, 0, 4);

        TextView bubble = new TextView(this);
        bubble.setText(content);
        bubble.setTextSize(15f);
        bubble.setPadding(16, 12, 16, 12);
        bubble.setLineSpacing(4f, 1f);

        if ("user".equals(role)) {
            bubble.setBackgroundColor(Color.parseColor("#DCF8C6"));
            msgRow.setGravity(Gravity.END);
        } else if ("ai".equals(role)) {
            bubble.setBackgroundColor(Color.parseColor("#E8E8E8"));
            msgRow.setGravity(Gravity.START);
        } else {
            bubble.setBackgroundColor(Color.parseColor("#FFF3CD"));
            msgRow.setGravity(Gravity.CENTER);
            bubble.setTextSize(13f);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.75);
        bubble.setLayoutParams(lp);

        msgRow.addView(bubble);
        messageContainer.addView(msgRow);

        messageScrollView.post(() -> messageScrollView.fullScroll(View.FOCUS_DOWN));
    }

    // 移除最后一条系统消息（用于替换"正在思考..."）
    private void removeLastSystemMessage() {
        if (messageContainer.getChildCount() > 0) {
            messageContainer.removeViewAt(messageContainer.getChildCount() - 1);
        }
    }

    // ── 以下方法保持不变 ──
    private void initChatSessions() {
        chatSessions.add(new ChatSession("自由对话"));
        chatSessions.add(new ChatSession("朋友A（俄语）"));
        chatSessions.add(new ChatSession("朋友B（西班牙语）"));
    }

    private void refreshDrawerList() {
        if (drawerContent.getChildCount() > 1) {
            drawerContent.removeViews(1, drawerContent.getChildCount() - 1);
        }
        for (ChatSession session : chatSessions) {
            TextView itemView = new TextView(this);
            itemView.setText("👤 " + session.name);
            itemView.setTextSize(18f);
            itemView.setPadding(20, 30, 20, 30);
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
                            }
                        })
                        .show();
                return true;
            });
            drawerContent.addView(itemView);
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
                        if (currentChatName.equals(session.name)) switchToChat(session);
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
                currentChatName = "新对话";
                ((TextView) drawerLayout.findViewWithTag("chatTitle")).setText("当前: " + currentChatName);
                Toast.makeText(this, "已清空屏幕，开启新对话", Toast.LENGTH_SHORT).show();
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
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    static class ChatSession {
        String name;
        ChatSession(String name) { this.name = name; }
    }
}

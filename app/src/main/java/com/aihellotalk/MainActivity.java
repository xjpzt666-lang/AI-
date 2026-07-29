package com.aihellotalk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.drawerlayout.widget.DrawerLayout;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private DrawerLayout drawerLayout;
    private LinearLayout messageContainer;
    private ScrollView messageScrollView;
    private EditText inputBox;
    private Spinner promptSpinner;
    private Button sendBtn;
    private LinearLayout drawerContent; // 侧滑菜单的容器

    private String currentChatName = "自由对话";
    private List<ChatSession> chatSessions = new ArrayList<>();

    private String[] promptNames = {"自由对话", "英语", "俄语", "乌克兰语", "西班牙语"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        // 指令选择器
        LinearLayout promptBar = new LinearLayout(this);
        promptBar.setOrientation(LinearLayout.HORIZONTAL);
        promptBar.setPadding(12, 4, 12, 4);
        promptBar.setBackgroundColor(Color.parseColor("#F0F0F0"));
        TextView promptLabel = new TextView(this);
        promptLabel.setText("指令: ");
        promptLabel.setTextSize(14f);
        promptSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, promptNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        promptSpinner.setAdapter(adapter);
        promptSpinner.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        promptBar.addView(promptLabel);
        promptBar.addView(promptSpinner);
        mainContent.addView(promptBar);

        // 底部输入区
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(12, 8, 12, 8);
        bottomBar.setBackgroundColor(Color.parseColor("#E0E0E0"));

        inputBox = new EditText(this);
        inputBox.setHint("输入消息...");
        inputBox.setBackgroundColor(Color.WHITE);
        inputBox.setPadding(20, 20, 20, 20);
        inputBox.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendBtn = new Button(this);
        sendBtn.setText("发送");
        sendBtn.setOnClickListener(v -> sendMessage());

        bottomBar.addView(inputBox);
        bottomBar.addView(sendBtn);
        mainContent.addView(bottomBar);

        drawerLayout.addView(mainContent);

        // ── 侧滑菜单区 ──
        drawerContent = new LinearLayout(this);
        drawerContent.setOrientation(LinearLayout.VERTICAL);
        drawerContent.setPadding(20, 50, 20, 20);
        drawerContent.setBackgroundColor(Color.parseColor("#FAFAFA"));
        
        // 修复之前报错的宽度设置，直接使用 width
        DrawerLayout.LayoutParams lp = new DrawerLayout.LayoutParams(dpToPx(280), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.LEFT;
        drawerContent.setLayoutParams(lp);

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("长按列表项可操作");
        drawerTitle.setTextSize(14f);
        drawerTitle.setTextColor(Color.GRAY);
        drawerTitle.setPadding(0,0,0,20);
        drawerContent.addView(drawerTitle);

        refreshDrawerList(); // 渲染列表

        drawerLayout.addView(drawerContent);
        setContentView(drawerLayout);
    }

    // 初始化假数据
    private void initChatSessions() {
        chatSessions.add(new ChatSession("自由对话"));
        chatSessions.add(new ChatSession("朋友A（俄语）"));
        chatSessions.add(new ChatSession("朋友B（西班牙语）"));
    }

    // 刷新侧滑菜单列表
    private void refreshDrawerList() {
        // 保留第一个 Title，移除后面的所有旧列表项
        if (drawerContent.getChildCount() > 1) {
            drawerContent.removeViews(1, drawerContent.getChildCount() - 1);
        }

        for (ChatSession session : chatSessions) {
            TextView itemView = new TextView(this);
            itemView.setText("👤 " + session.name);
            itemView.setTextSize(18f);
            itemView.setPadding(20, 30, 20, 30);
            itemView.setTextColor(Color.BLACK);

            // 【单击】：切换对话
            itemView.setOnClickListener(v -> {
                switchToChat(session);
                drawerLayout.closeDrawers();
            });

            // 【长按】：弹出 重命名/删除 菜单
            itemView.setOnLongClickListener(v -> {
                String[] options = {"重命名", "删除"};
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("操作: " + session.name)
                        .setItems(options, (dialog, which) -> {
                            if (which == 0) {
                                showRenameDialog(session);
                            } else if (which == 1) {
                                chatSessions.remove(session);
                                refreshDrawerList(); // 重新渲染列表
                                Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .show();
                return true;
            });

            drawerContent.addView(itemView);
        }
    }

    // 重命名弹窗
    private void showRenameDialog(ChatSession session) {
        final EditText input = new EditText(this);
        input.setText(session.name);
        input.setSelection(session.name.length()); // 光标移到最后

        new AlertDialog.Builder(this)
                .setTitle("重命名对话")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        session.name = newName;
                        refreshDrawerList(); // 重新渲染列表
                        Toast.makeText(MainActivity.this, "已重命名为: " + newName, Toast.LENGTH_SHORT).show();
                        
                        // 如果当前正好在聊这个对话，把头部的标题也改了
                        if (currentChatName.equals(session.name)) {
                            switchToChat(session); 
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 右上角 ⋮ 菜单
    private void showPopupMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenu().add(0, 1, 0, "💬 开启新对话");
        popup.getMenu().add(0, 2, 0, "⚙️ 设置/API配置"); 
        
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                // 开启新对话
                messageContainer.removeAllViews();
                currentChatName = "新对话";
                ((TextView) findViewWithTag("chatTitle")).setText("当前: " + currentChatName);
                Toast.makeText(this, "已清空屏幕，开启新对话", Toast.LENGTH_SHORT).show();
                return true;
            } else if (item.getItemId() == 2) {
                // 跳转到 SettingsActivity 页面
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
                return true;
            }
            return false;
        });
        popup.show();
    }

    // 切换对话
    private void switchToChat(ChatSession session) {
        currentChatName = session.name;
        TextView chatTitle = (TextView) findViewWithTag("chatTitle");
        if (chatTitle != null) {
            chatTitle.setText("当前: " + currentChatName);
        }
        messageContainer.removeAllViews();
    }

    // 发送消息上屏
    private void sendMessage() {
        String text = inputBox.getText().toString().trim();
        if (text.isEmpty()) return;

        // 用户消息
        TextView userMsg = new TextView(this);
        userMsg.setText("我: " + text);
        userMsg.setTextSize(16f);
        userMsg.setTextColor(Color.parseColor("#333333"));
        userMsg.setPadding(0, 20, 0, 10);
        messageContainer.addView(userMsg);
        
        inputBox.setText("");

        // 模拟AI消息 (后续可以在这里接你的 AITranslator)
        TextView aiMsg = new TextView(this);
        aiMsg.setText("AI回复: 测试内容 (待接入真实API)");
        aiMsg.setTextSize(16f);
        aiMsg.setTextColor(Color.parseColor("#0066CC"));
        aiMsg.setPadding(0, 10, 0, 20);
        messageContainer.addView(aiMsg);

        messageScrollView.post(() -> messageScrollView.fullScroll(View.FOCUS_DOWN));
    }

    // dp 转 px 工具 (解决报错)
    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    // 会话数据结构
    static class ChatSession {
        String name;
        public ChatSession(String name) {
            this.name = name;
        }
    }
}

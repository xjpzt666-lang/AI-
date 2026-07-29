package com.aihellotalk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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

    private String currentChatName = "自由对话";
    private String currentPrompt = "";

    private List<ChatSession> chatSessions = new ArrayList<>();

    private String[] promptNames = {"自由对话", "英语翻译", "俄语翻译", "乌克兰语翻译", "日语翻译"};
    private String[] promptValues = {"", "", "", "", ""};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initChatSessions();

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
        leftMenuBtn.setOnClickListener(v -> drawerLayout.openDrawer(Gravity.LEFT));

        TextView title = new TextView(this);
        title.setText("HT AI 翻译");
        title.setTextSize(18f);
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button rightMenuBtn = new Button(this);
        rightMenuBtn.setText("⋮");
        rightMenuBtn.setTextSize(28f);
        rightMenuBtn.setOnClickListener(v -> showPopupMenu(v));

        topBar.addView(leftMenuBtn);
        topBar.addView(title);
        topBar.addView(rightMenuBtn);
        mainContent.addView(topBar);

        // 对话标题
        TextView chatTitle = new TextView(this);
        chatTitle.setText("当前: " + currentChatName);
        chatTitle.setTag("chatTitle");
        chatTitle.setPadding(16, 12, 16, 12);
        chatTitle.setTextSize(14f);
        chatTitle.setTextColor(Color.GRAY);
        chatTitle.setBackgroundColor(Color.parseColor("#F9F9F9"));
        mainContent.addView(chatTitle);

        // 消息列表
        messageScrollView = new ScrollView(this);
        messageScrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        messageScrollView.setPadding(16, 8, 16, 8);

        messageContainer = new LinearLayout(this);
        messageContainer.setOrientation(LinearLayout.VERTICAL);
        messageScrollView.addView(messageContainer);
        mainContent.addView(messageScrollView);

        // Prompt 选择器
        LinearLayout promptBar = new LinearLayout(this);
        promptBar.setOrientation(LinearLayout.HORIZONTAL);
        promptBar.setPadding(12, 4, 12, 4);
        promptBar.setBackgroundColor(Color.parseColor("#F0F0F0"));

        TextView promptLabel = new TextView(this);
        promptLabel.setText("指令: ");
        promptLabel.setTextSize(14f);
        promptLabel.setGravity(Gravity.CENTER_VERTICAL);

        promptSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, promptNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        promptSpinner.setAdapter(adapter);
        promptSpinner.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        promptBar.addView(promptLabel);
        promptBar.addView(promptSpinner);
        mainContent.addView(promptBar);

        // 底部输入框
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(12, 8, 12, 8);
        bottomBar.setBackgroundColor(Color.parseColor("#F0F0F0"));

        inputBox = new EditText(this);
        inputBox.setHint("输入内容...");
        inputBox.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendBtn = new Button(this);
        sendBtn.setText("发送");
        sendBtn.setOnClickListener(v -> sendMessage());

        bottomBar.addView(inputBox);
        bottomBar.addView(sendBtn);
        mainContent.addView(bottomBar);

        drawerLayout.addView(mainContent);

        // 侧滑菜单
        LinearLayout drawerContent = new LinearLayout(this);
        drawerContent.setOrientation(LinearLayout.VERTICAL);
        drawerContent.setPadding(20, 50, 20, 20);
        drawerContent.setBackgroundColor(Color.parseColor("#FAFAFA"));
        drawerContent.setLayoutParams(new DrawerLayout.LayoutParams(
                320, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("对话列表");
        drawerTitle.setTextSize(18f);
        drawerTitle.setTextColor(Color.BLACK);
        drawerContent.addView(drawerTitle);

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(Color.parseColor("#DDDDDD"));
        drawerContent.addView(divider);

        for (ChatSession session : chatSessions) {
            View itemView = createDrawerItem(session.name, session.promptName);
            itemView.setOnClickListener(v -> {
                switchToChat(session);
                drawerLayout.closeDrawers();
            });
            drawerContent.addView(itemView);
        }

        drawerLayout.addView(drawerContent);

        DrawerLayout.LayoutParams lp = (DrawerLayout.LayoutParams) drawerContent.getLayoutParams();
        lp.gravity = Gravity.LEFT;
        drawerContent.setLayoutParams(lp);

        setContentView(drawerLayout);
    }

    private void initChatSessions() {
        chatSessions.add(new ChatSession("自由对话", "自由对话", ""));
        chatSessions.add(new ChatSession("朋友A（俄语）", "俄语翻译", ""));
        chatSessions.add(new ChatSession("朋友B（乌克兰语）", "乌克兰语翻译", ""));
        chatSessions.add(new ChatSession("朋友C（中文）", "英语翻译", ""));
    }

    private View createDrawerItem(String name, String promptName) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setPadding(0, 12, 0, 12);

        TextView nameText = new TextView(this);
        nameText.setText(name);
        nameText.setTextSize(15f);
        nameText.setTextColor(Color.DKGRAY);
        nameText.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView promptTag = new TextView(this);
        promptTag.setText("[" + promptName + "]");
        promptTag.setTextSize(12f);
        promptTag.setTextColor(Color.GRAY);

        item.addView(nameText);
        item.addView(promptTag);
        return item;
    }

    private void switchToChat(ChatSession session) {
        currentChatName = session.name;
        currentPrompt = session.prompt;

        TextView chatTitle = (TextView) drawerLayout.findViewWithTag("chatTitle");
        if (chatTitle != null) {
            chatTitle.setText("当前: " + currentChatName);
        }

        messageContainer.removeAllViews();
        addMessage("system", "已切换到「" + currentChatName + "」对话");

        for (int i = 0; i < promptNames.length; i++) {
            if (promptNames[i].equals(session.promptName)) {
                promptSpinner.setSelection(i);
                break;
            }
        }
    }

    private void sendMessage() {
        String text = inputBox.getText().toString().trim();
        if (text.isEmpty()) return;

        addMessage("user", text);
        inputBox.setText("");

        int selectedPos = promptSpinner.getSelectedItemPosition();
        String selectedPrompt = promptValues[selectedPos];

        String reply = simulateAIResponse(text, selectedPrompt);
        addMessage("ai", reply);
    }

    private void addMessage(String role, String content) {
        LinearLayout msgRow = new LinearLayout(this);
        msgRow.setOrientation(LinearLayout.HORIZONTAL);
        msgRow.setPadding(0, 4, 0, 4);

        TextView bubble = new TextView(this);
        bubble.setText(content);
        bubble.setTextSize(15f);
        bubble.setPadding(16, 12, 16, 12);
        bubble.setLineSpacing(4f, 1f);

        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(16f);
        if ("user".equals(role)) {
            drawable.setColor(Color.parseColor("#DCF8C6"));
            msgRow.setGravity(Gravity.END);
        } else if ("ai".equals(role)) {
            drawable.setColor(Color.parseColor("#E8E8E8"));
            msgRow.setGravity(Gravity.START);
        } else {
            drawable.setColor(Color.parseColor("#FFF3CD"));
            msgRow.setGravity(Gravity.CENTER);
            bubble.setTextSize(13f);
        }
        bubble.setBackground(drawable);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.7);
        bubble.setLayoutParams(lp);

        msgRow.addView(bubble);
        messageContainer.addView(msgRow);

        messageScrollView.post(() -> messageScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private String simulateAIResponse(String userText, String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return "你好！我是 AI 助手。你说了: " + userText;
        } else {
            return "[使用指令翻译]\n" + userText + "\n→ 翻译结果（待接入真实 API）";
        }
    }

    private void showPopupMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.main_popup_menu, popup.getMenu());

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            } else if (id == R.id.action_new_chat) {
                ChatSession newSession = new ChatSession("新对话", "自由对话", "");
                chatSessions.add(newSession);
                switchToChat(newSession);
                Toast.makeText(this, "已创建新对话", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_temp) {
                Toast.makeText(this, "温度设置待实现", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_token) {
                Toast.makeText(this, "Token 设置待实现", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_context) {
                Toast.makeText(this, "上下文数设置待实现", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });

        popup.show();
    }

    private static class ChatSession {
        String name;
        String promptName;
        String prompt;

        ChatSession(String name, String promptName, String prompt) {
            this.name = name;
            this.promptName = promptName;
            this.prompt = prompt;
        }
    }
}

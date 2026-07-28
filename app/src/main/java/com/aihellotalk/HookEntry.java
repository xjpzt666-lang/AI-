package com.aihellotalk;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"com.hellotalk".equals(lpp.packageName)) return;

        XposedBridge.log("HT_AI =====================");
        XposedBridge.log("HT_AI 模块启动 v5.0");

        String apiKey = "";
        String apiUrl = "https://api.openai.com/v1/chat/completions";
        String model = "";

        // 读取配置文件 /data/local/tmp/htai_config.txt
        File cfgFile = new File("/data/local/tmp/htai_config.txt");
        if (cfgFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(cfgFile))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("api_key=") && line.length() > 8) {
                        apiKey = line.substring(8);
                    } else if (line.startsWith("api_url=") && line.length() > 8) {
                        apiUrl = line.substring(8);
                    } else if (line.startsWith("model=") && line.length() > 6) {
                        model = line.substring(6);
                    }
                }
            } catch (Exception e) {
                XposedBridge.log("HT_AI 读配置失败: " + e.getMessage());
            }
        }

        if (apiUrl.isEmpty()) {
            apiUrl = "https://api.openai.com/v1/chat/completions";
        }

        XposedBridge.log("HT_AI Key长度: " + apiKey.length() + " model: " + model);

        AITranslator.init(apiKey, apiUrl, model);
        ChatHook.install(lpp.classLoader);

        XposedBridge.log("HT_AI 初始化完成");
    }
}

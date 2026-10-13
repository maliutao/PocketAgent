# PocketAgent

自用 Android 手机智能体:技能优先 + 经验沉淀。

- PRD: [个人手机 Agent(PocketAgent)PRD v0.1.md](./个人手机%20Agent(PocketAgent)PRD%20v0.1.md)
- 推送到 `main` 后,GitHub Actions 自动构建 debug APK(Artifacts 里下载)
- API Key 只存在手机本地加密存储中,**绝不提交到仓库**

## 当前进度

- [x] M0 工程与 CI
- [x] M1 对话 + 系统工具(音量/闹钟/打开应用/天气/语音输入)
- [ ] M2 无障碍 + 通用 Agent 循环
- [ ] M3 技能引擎 + 小红书检索

## 本地构建

```
./gradlew assembleDebug
# 需要 JDK 17 与 Android SDK(compileSdk 34)
# SDK 路径写入 local.properties: sdk.dir=D:\\Android\\Sdk
```

## 使用

1. 安装 APK,首次进入点右上角「设置」
2. 填写 LLM 配置(OpenAI 兼容协议):Base URL / API Key / 模型名
   例如 DeepSeek:`https://api.deepseek.com/v1` + `sk-…` + `deepseek-chat`
3. 回到主界面下指令,如:「明早 7 点叫我,音量调到 30%」

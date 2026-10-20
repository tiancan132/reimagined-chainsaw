# 读阅 · Duyue Reader

一个专注「听书」的中文 Android 阅读器。支持 TXT / EPUB / PDF，调用**系统本地 TTS 引擎**朗读，带**桌面歌词式悬浮字幕**，并可接入 DeepSeek 做 **AI 章节预习**。

> 为长时间听书场景设计：可以关掉屏幕、切到其他 App、甚至玩游戏，朗读和字幕都不会中断。

---

## 功能

### 📖 阅读

- **TXT / EPUB / PDF** 导入，自动识别章节
- 正文章节列表支持**分卷折叠**，自动定位当前章节
- 字号、行距、主题（日间 / 夜间 / 护眼）可调
- **自动记住阅读位置**：退回书架或重开 App 都会回到上次读到的地方
- 顶部显示「第 N 章 / 共 M 章 · 本章进度 · 全书进度」，并可直接切换上一章 / 下一章

### 🎧 听书

- 使用**手机自带的 TTS 引擎**（小爱、讯飞、Google TTS 等），不经过云端，离线可用
- 可切换引擎、调节**语速**与**音调**
- **逐字同步高亮**（依赖引擎的 `onRangeStart` 回调，不支持的引擎会退化为整句高亮）
- 前台服务 + 通知栏控制（上一句 / 暂停 / 下一句 / 停止），切后台、锁屏都能继续读
- **章末自动续读**：一章读完自动进入下一章

### 💬 桌面歌词悬浮窗

- 悬浮在所有 App 之上的字幕条，看别的 App 时也能跟着听
- **背景透明度可调**（0% 全透明 ~ 100% 深色底）
- 支持拖动位置，点击展开控制面板（上一句 / 播放暂停 / 下一句 / 关闭）

### 🤖 AI 章节预习

- 接入 **DeepSeek API**（OpenAI 兼容接口），一键生成本章内容总结
- 支持流式输出，边生成边显示
- 可自动拉取模型列表、一键测试连接
- 总结结果按章节缓存，不重复消耗 token

### ✍️ 选中文字快捷操作

- **长按选中**文字后，底部弹出快捷条
- 可配置跳转到任意支持接收文字的 App（DeepSeek、各类词典等）
- 内置「复制」「搜索」，以及「▶ 从这里读」
- **点击正文任意句子即可从该句开始朗读**（朗读进行中）

---

## 截图

> 暂未提供。欢迎 PR 补充。

---

## 安装

### 方式一：直接下载 APK

前往 [Releases](../../releases) 页面下载最新 APK，安装即可。

> 首次使用听书功能需要授予「显示在其他应用上层」权限（用于悬浮字幕）。

### 方式二：自己编译

```bash
git clone https://github.com/tiancan132/reimagined-chainsaw.git
cd reimagined-chainsaw
```

用 Android Studio 打开，或命令行编译：

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

**环境要求**

| 项目 | 版本 |
|---|---|
| JDK | 17+ |
| Android SDK | API 34 |
| Gradle | 8.7（wrapper 已内置） |
| AGP | 8.5.2 |
| Kotlin | 1.9.24 |

---

## 使用说明

### 1. 导入书籍

首页点击「导入」，选择 TXT / EPUB / PDF 文件。

### 2. 开启悬浮字幕

- 首次打开需要授予**悬浮窗权限**（设置 → 应用 → 显示在其他应用上层）
- 在 App 设置中打开「在其他应用上显示字幕」
- 背景透明度可在同一页面调节

### 3. 配置 AI 总结（可选）

1. 前往 [DeepSeek 开放平台](https://platform.deepseek.com/) 获取 API Key
2. App 设置 → AI 章节预习 → 填入 API Key
3. 点击「获取模型」自动拉取可用模型，或手动填写
4. 点击「测试连接」确认可用

> API Key 仅保存在本机 SharedPreferences，不会上传到任何服务器。

### 4. 选择 TTS 引擎

设置 → TTS 引擎，从系统已安装的引擎中选择。建议选择中文语音质量较好的引擎（如讯飞、小爱）。

---

## 交互说明

| 操作 | 效果 |
|---|---|
| 点击正文某句 | 朗读进行中：从该句开始读；未朗读：无反应 |
| 长按正文某句 | 选中该句，弹出底部快捷条 |
| 长按后拖动 | 扩大 / 缩小选中范围 |
| 底部播放按钮 | 播放 / 暂停；未播放时从当前屏幕顶部开始读 |
| 快捷条「▶ 从这里读」 | 从选中处开始朗读 |
| 点击顶部「上一章 / 下一章」 | 切换章节并继续朗读 |

---

## 项目结构

```
app/src/main/java/com/duyue/reader/
├── MainActivity.kt          # 主界面、路由、播放状态管理
├── ai/
│   └── AiSummaryClient.kt   # DeepSeek 流式总结
├── data/
│   ├── LibraryStore.kt      # 书架存储（索引 + 分书正文）
│   └── Prefs.kt             # 偏好设置
├── floatwin/
│   └── LyricOverlay.kt      # 桌面歌词悬浮窗
├── model/
│   └── Book.kt              # 书籍 / 章节 / 朗读片段模型
├── parser/
│   ├── TxtParser.kt         # TXT 章节切分（支持中文数字、卷、部等）
│   ├── EpubParser.kt        # EPUB 解析
│   ├── PdfParser.kt         # PDF 文本提取
│   └── FileReaderUtil.kt    # 编码探测（UTF-8 / GBK）
├── textaction/
│   └── TextActionHandler.kt # ACTION_PROCESS_TEXT / ACTION_SEND 分发
├── tts/
│   ├── TtsController.kt     # 系统 TTS 封装（逐句提交 + 高亮同步）
│   └── TtsPlaybackService.kt# 前台服务 + 通知栏控制
└── ui/
    ├── ReaderScreen.kt      # 阅读页（滚动、选字、播放栏、目录）
    ├── BookshelfScreen.kt   # 书架
    ├── SettingsSheet.kt     # 设置
    ├── AiSummarySheet.kt    # AI 总结弹窗
    ├── TextTargetSheet.kt   # 快捷 App 配置
    └── ReaderTheme.kt       # 主题定义
```

### 关键设计

- **朗读片段（Clip）**：正文按标点切成 8~35 字的片段，每个片段记录它属于第几段，保证点击定位精确
- **逐句提交 TTS**：每次只 `speak` 一句，不用 `QUEUE_ADD` 排队，避免高亮跳错
- **章节级文件存储**：书籍正文按章存储，避免大文件一次性读入内存导致 OOM

---

## 已知限制

- **PDF 仅支持文本型**，扫描版（图片）无法提取文字
- 悬浮字幕依赖系统 `TYPE_APPLICATION_OVERLAY` 权限；部分深度定制系统（如 MIUI、Flyme）可能需要在系统设置中额外允许「后台弹窗」
- 逐字高亮依赖 TTS 引擎的 `onRangeStart` 回调，国产引擎支持程度不一
- 前台服务通知在部分系统上可能被清理，建议将本 App 加入电池优化白名单

---

## 参与贡献

欢迎提交 Issue 和 Pull Request。

1. Fork 本仓库
2. 创建分支：`git checkout -b feature/xxx`
3. 提交改动：`git commit -m "feat: xxx"`
4. 推送分支：`git push origin feature/xxx`
5. 提交 Pull Request

---

## 致谢

- [Jetpack Compose](https://developer.android.com/jetpack/compose) —— UI
- [OkHttp](https://square.github.io/okhttp/) —— 网络请求
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) —— JSON
- [jsoup](https://jsoup.org/) —— EPUB / HTML 解析
- [DeepSeek](https://www.deepseek.com/) —— AI 章节总结

---

## 许可证

[MIT License](LICENSE) © 2026 tiancan132

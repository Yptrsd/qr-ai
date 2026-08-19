# QR-AI
> **船新赤石科技 ```QR-AI``` 在 ```DeepSeek Harness```、```Xiaomi MiMo V2.5``` 与 ```DeepSeek-V4-Flash``` 的辅助下正式诞生！**
>
> 在这个项目中，你可以见到**包括但不限于：**
> - ```≤100kb``` 的传奇应用体积
> - 纯机写的**不完全** ```Markdown``` 与 ```LaTeX``` 支持
> - **强行适配、全靠硬配**的联网搜索与API服务
> - 0 外部依赖，真正做到清空```Third-party.md```
> - **外星科技**上滑清屏
> - 拒绝使用 ```Flutter``` 与 ```Dart``` ，纯 ```Kotlin``` 实现，真正杜绝跨平台问题
> - 启动之快宛如**看到核弹爆炸、瘫坐在椅子上**！


**轻量、零依赖的原生 Android LLM 聊天客户端** · **A minimal, zero-dependency native Android LLM chat client**

QR-AI 是一个原生 Android（Kotlin）应用，用于和各类大语言模型（LLM）进行**流式对话**。它**零第三方依赖**——所有网络、Markdown/LaTeX 渲染、界面全部使用 Android SDK 自带能力实现，APK 小巧、启动迅速。

QR-AI is a native Android (Kotlin) app for **streaming conversations** with any LLM provider. It ships with **zero third-party dependencies** — networking, Markdown/LaTeX rendering, and the entire UI are built entirely on the Android SDK, so the APK stays tiny and launches fast.

---

## ✨ 特性 · Features

- 🧩 **多厂商支持** · Multi-provider support — 内置 DeepSeek、OpenAI、Anthropic、小米 MiMo、Kimi、GLM、Grok、Gemini 预设，也可自定义任意 OpenAI 兼容接口。
- ⚡ **流式对话** · Streaming responses — SSE 流式输出，实时逐字显示。
- 🌐 **联网搜索** · Web search — 三种模式：离线 / 强制搜索 / 自动（由模型自主决定），按厂商注入对应联网模板。
- 💭 **思考模式** · Think mode — 五档调节（关/低/中/高/最大），映射为各端点的 thinking / reasoning 参数。
- 🧮 **Markdown + LaTeX 渲染** · Built-in Markdown & LaTeX renderer — 纯 Spannable 实现，支持标题、列表、代码块、表格、行内代码、加粗/斜体、删除线、链接、上下标、行内公式与 `\frac` 等常见 LaTeX。
- 📦 **零依赖** · Zero dependencies — 无第三方库，`dependencies {}` 为空。
- 🌏 **中英双语** · Bilingual UI — 简体中文 + English，`resConfigs("zh", "en")` 控制资源体积。

---

## 📋 技术栈 · Tech Stack

| 项目 Item | 值 Value |
| --- | --- |
| 语言 Language | Kotlin (JVM 11 / 工具链 25) |
| UI | 原生 View（无 Compose） |
| 最小 API · minSdk | 24 (Android 7.0) |
| 目标 / 编译 API | targetSdk / compileSdk 36 |
| 构建 Build | Gradle 9.7 + AGP 9.3.1 |
| 依赖 Dependencies | **无**（全部 Android SDK 自带） |

---

## 🚀 快速开始 · Getting Started

### 环境要求 · Prerequisites

- JDK 25（`build.gradle.kts` 中 `jvmToolchain(25)`）
- Android SDK（compileSdk 36）
- 一个支持 OpenAI 兼容接口的 LLM API 的 **API Key**

### 构建与运行 · Build & Run

```bash
# 用 Android Studio 打开根目录，或命令行：
./gradlew assembleDebug    # 构建调试版
./gradlew installDebug     # 安装到已连接的设备/模拟器
```

> 需要的构建产物位于 `app/build/outputs/apk/*/`。

### 首次使用 · First Run

1. 打开应用，点击右上角 **⚙ 设置**。
2. 选择一个 **服务商预设**（或选"自定义"），填入 **API Key**、**Base URL**，点击"获取"拉取模型列表（也可手动输入模型名）。
3. 配置 **端点类型**：`chat` / `responses` / `auto`（自动探测，失败自动回退）。
4. （可选）填写 **联网搜索模板** JSON，例如 `{"tools":[{"type":"web_search"}],"tool_choice":"auto"}`，为空则不支持联网。
5. 保存并返回，即可开始对话。

**管理多个 API**：点击编辑、长按删除、点名称前圆圈切换选中。

> **注意 · Note**：旧版 "reasoning" 推理模型默认被过滤禁用——V4 时代的"思考"由 `thinking` / `reasoning` 参数控制，而不是模型名。

---

## 🧭 使用说明 · Usage

- **发送**：输入问题后点击发送按钮或按回车。
- **流式展示**：AI 回复边生成边显示，完成后自动渲染为 Markdown。
- **上滑清屏**：滚动到底部后快速上滑，清空聊天区。
- **联网按钮**：点击循环切换 离线 🚫 → 联网 🌐 → 自动 🌐。
- **思考滑杆**：设置页左侧拖动调节思考强度。
- **快捷命令**：
  - `/clear` — 清空对话历史与消息区
  - `/quit` 或 `/exit` — 退出应用

---

## 🗂️ 项目结构 · Project Structure

```
qr-ai/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/qrai/
│       │   ├── MainActivity.kt      # 主聊天界面 + 流式请求/SSE 解析
│       │   ├── SettingsActivity.kt  # API 配置 / 多厂商管理
│       │   └── Markdown.kt          # 零依赖 Markdown + LaTeX 渲染器
│       └── res/                     # 布局、可绘制、多语言资源
├── gradle/
├── build.gradle.kts                 # 根构建脚本（插件管理/仓库）
└── settings.gradle.kts
```

### 核心实现 · Core Modules

| 文件 File | 职责 Responsibility |
| --- | --- |
| `MainActivity.kt` | 聊天 UI、SSE 流式请求与解析、思考模式、联网搜索注入、上滑清屏 |
| `SettingsActivity.kt` | 多 API 管理（预设/编辑/删除/选中）、模型列表拉取、System Prompt 配置 |
| `Markdown.kt` | 纯 Spannable 的 Markdown + 轻量 LaTeX 渲染器 |

### 请求架构 · Request Architecture

- 支持两种端点：**Chat Completions**（`/chat/completions`）与 **Responses API**（`/responses`），`auto` 模式优先尝试 responses 并自动回退 chat。
- SSE 解析兼容 `chat` 与 `responses` 两种事件格式，思考过程与正文分别缓冲。
- 联网搜索通过向请求体注入 tools 模板实现，并按用户选择的联网模式设置 `force_search` / `tool_choice`。

---

## 🔧 构建配置 · Notes on Build

- `resConfigs("zh", "en")`：仅打包中英文资源，减小体积。
- Release 构建开启 `minifyEnabled` + `shrinkResources`（ProGuard/R8 混淆）。
- 当前 `debug` 构建的 release 包**临时使用 debug 签名**，便于直接安装测试体积（正式发布请替换为正式签名）。

---

## ⚠️ 免责声明 · Disclaimer

> **本项目仅为个人学习与娱乐用途开发，其架构设计、代码组织方式及实现逻辑不构成任何工程实践建议，不应被视作可直接复用的范例或参考实现。**
>
> 本项目采用了生成式AI辅助生成。

- **内容仅供参考**：AI 模型存在误报，不能保证其回答完全正确。任何回答都不构成专业建议（包括但不限于医疗、法律和投资）。由盲目相信生成内容造成的一切后果由用户自负，开发者不提供任何担保。
- **API Key 与隐私**：你的 API Key 由 App 通过直连请求直接发给对应厂商服务器，不走任何中转。默认走 HTTP(S)，若厂商域名不支持 HTTPS（`usesCleartextTraffic=true`），密钥会以明文传输——**请只在可信任的网络下配置真实 Key**，切勿在公共 Wi-Fi 上填入付费账号的密钥。本 App **不会**上传你的 API Key 或对话内容到任何第三方。
- **联网搜索与厂商差异**：各厂商的联网模板 / 端点（chat、responses、auto）行为各有差异，联网结果不代表本项目立场。遇到厂商接口变更或协议不兼容，开发者可能没有精力处置，恕不保证实时同步。若发现以上问题，请提出 ```Issues``` 或修改后提交 ```Pull Requests```。
- **网络与错误**：网络波动、接口限流、模型故障或各种已知或未知的情况都可能让你看到 `❌ 错误` 红字，属于随机情况，无确定规避方案。
- **性能与安全性**：本项目零依赖、体积小巧，但「小 ≠ 稳固」。请勿用于任何生产环境、医疗设备、航班驾驶或任何会让你对着一颗 AI 负责的场景。
- **不担保条款**：本软件按「原样」（AS IS）提供，**开发者不作任何明示或默示的担保**，包括但不限于对适销性、特定用途适用性、不侵权或持续运行的保证。使用本软件所产生的一切风险与损失由用户自行承担，开发者在任何情况下均不对因使用或无法使用本软件而产生的任何直接、间接、附带、特殊或后果性损害负责。

## 📄 License

本项目基于 [The Unlicense](https://unlicense.org) 发布，为公有领域（Public Domain）软件。详见 [LICENSE](LICENSE)。

Released into the public domain under **The Unlicense**. See [LICENSE](LICENSE).

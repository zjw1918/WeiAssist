# 小维助手 WeiAssist 🐟 — 装在你 Android 手机上的 AI 助手

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: light)" srcset="docs/images/banner-zh-light.png">
    <img src="docs/images/banner-zh-dark.png" alt="小维助手（WeiAssist），开源的 Android AI 助手。AI 引擎：Pi 和 DeepSeek Harness。Linux 工具：Termux。">
  </picture>
</p>

<p align="center">
  <a href="https://github.com/zjw1918/WeiAssist/releases/latest"><img src="https://img.shields.io/github/v/release/zjw1918/WeiAssist?style=flat-square&label=release" alt="最新版本"></a>
  <a href="https://github.com/zjw1918/WeiAssist/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/zjw1918/WeiAssist/ci.yml?branch=main&style=flat-square&label=ci" alt="CI 状态"></a>
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="许可证：Apache 2.0"></a>
</p>

<p align="center"><a href="README.md">English</a> · <b>简体中文</b></p>

小维助手是一个开源的、运行在手机上的 AI 助手。你可以用自己选的 AI 服务和它聊天，发给它照片和文件，让它运行 Linux 命令、替你操作手机上的应用，还能同时进行多个对话。不在手机旁边？用 Telegram 也能找它。

**AI 引擎由你选。** 智能体循环可以跑在内置的 [Pi](https://github.com/earendil-works/pi) 上，也可以跑在 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 上（应用会帮你安装）。中间没有我们的服务器：应用直接连接你的 AI 服务，密钥只保存在手机上。

[下载](https://github.com/zjw1918/WeiAssist/releases/latest) · [Termux 设置](docs/TERMUX.md) · [Telegram](docs/TELEGRAM.md) · [架构](docs/ARCHITECTURE.md) · [进度](docs/PROGRESS.md) · [路线图](docs/ROADMAP.md)（文档为英文）

## 安装

从 [Releases](https://github.com/zjw1918/WeiAssist/releases/latest) 下载 APK 并打开。Android 询问时，允许浏览器或文件管理器安装应用。

## 快速开始

1. 打开小维助手，连接一个 AI 服务：用 ChatGPT、SuperGrok 或 X Premium 账号登录，或粘贴 API 密钥。
2. 新建一个对话，问它点什么。
3. 可选，都在 **设置** 里：
   - **Linux 命令：** 按引导设置 [Termux](https://termux.dev)，它就能查资料、处理数据、写代码并运行。
   - **AI 引擎：** 继续用 Pi，或安装 DeepSeek Harness（需要 Termux，约 1.5 GB 空间，安装需 10–30 分钟）。
   - **手机任务：** 打开它的无障碍服务，让它操作手机上的应用。
   - **Telegram：** 配对一个机器人，随时随地找它（[说明](docs/TELEGRAM.md)）。

## AI 引擎

| | **Pi** | **DeepSeek Harness** |
| --- | --- | --- |
| 安装 | 内置 | 在 设置 → AI 引擎 一键安装；在 Termux 里装好 Ubuntu、Node.js 和 dsh |
| 能力 | 精简、快速的智能体循环 | 自带文件、bash、子智能体、网页抓取、技能和工作流 |
| 版本 | 随应用附带 | 默认安装测试过的版本；也可以装更新的或任意版本，随时退回 |

两个引擎使用同样的 AI 服务、密钥和登录，也都有应用的手机工具和安全检查。每个对话一直使用它开始时的引擎。

## 它能做什么

![小维助手的四个界面：DeepSeek Harness 会话里三个子智能体并行调研；它的回答，把三份调研汇总成一张对比表；设置里的 AI 引擎选择（Pi 或 DeepSeek Harness）；以及 Pi 会话用 Termux 里的 curl 查天气](docs/images/showcase.png)

- **多家 AI 服务：** OpenAI、Anthropic、Google、DeepSeek、OpenRouter、xAI、Moonshot、Mistral、OpenCode，以及任何兼容 OpenAI 的服务（Ollama、vLLM、LM Studio 等）。
- **照片和文件：** 发给它图片、PDF 和文档；它也会把做好的文件发回给你。
- **手机上的 Linux：** 有了 Termux，它能上网查资料、分析数据、构建和运行代码，长任务在 `tmux` 里持续运行。
- **同时进行多个对话，** 每个都在后台运行、保留历史；还有一个总管对话，可以用打字或语音替你开始、查看和停止它们。
- **手机任务：** 它能操作你的应用，遇到发送、支付、删除这类操作会先问你。
- **中英文界面。**

## 用 Telegram 找它

<p align="center">
  <img src="docs/images/telegram-demo.gif" width="560" alt="Mac 上的 Telegram 和手机并排：问圣地亚哥的天气，助手用 Termux 回答；让它用 Gmail 发送天气预报，它在手机上写好邮件，点发送前在 Telegram 和手机上同时询问；在 Telegram 里点了拒绝，什么也没发出">
</p>

用 [BotFather](https://t.me/BotFather) 创建一个机器人，把它的 token 粘贴到 **设置 → Telegram**，再配对你的账号。每个聊天就是一个对话：回答实时显示，能收发照片和文件，有风险的操作前会先问你（✅ / ❌）。不需要服务器。详见 [docs/TELEGRAM.md](docs/TELEGRAM.md)。

## 隐私与安全

- 小维助手没有服务器。你的消息、附件、命令输出，以及手机任务中相关的屏幕内容，只会发给你选择的 AI 服务。密码输入框的内容永远不会发出。
- 登录信息只保存在手机上，用 Android Keystore 加密。DeepSeek Harness 通过应用访问你的 AI 服务，自己不持有任何密钥。
- 无论用哪个引擎，发送、支付、删除这类操作前都会先问你；随时可以停止。
- 使用 Telegram 时，消息还会经过 Telegram 的服务器，机器人聊天不是端到端加密的。

更多见 [SECURITY.md](SECURITY.md)。

## 文档

| 目标 | 从这里开始 |
| --- | --- |
| 设置 Linux 命令 | [docs/TERMUX.md](docs/TERMUX.md) |
| 用 Telegram 找它 | [docs/TELEGRAM.md](docs/TELEGRAM.md) |
| 了解整体架构 | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| 看已完成和接下来的工作 | [docs/PROGRESS.md](docs/PROGRESS.md) · [docs/ROADMAP.md](docs/ROADMAP.md) |
| 参与贡献 | [CONTRIBUTING.md](CONTRIBUTING.md) |

## 开发

需要：Android SDK（platform 37）、[`.nvmrc`](.nvmrc) 指定版本的 Node 和 npm，以及 JDK。

```bash
./gradlew assembleDebug          # 同时打包 Pi（pi-host/）和 DeepSeek Harness 桥接（dsh-host/）
./gradlew testDebugUnitTest lint
cd pi-host && npm test           # 内置智能体的端到端测试
cd dsh-host && npm test          # dsh 桥接测试；设置 DSH_RUN 和 DSH_HOME 后针对真实的 dsh 运行
```

推送版本标签（`git tag v0.2 && git push origin v0.2`）后，Release 工作流会构建并签名 APK。本地的
`assembleRelease` 只有找到 `keystore.properties`（在 `WEIASSIST_SIGNING` 指定的位置，或
`~/.android/weiassist-release/`）时才会签名。

## 许可证

[Apache License 2.0](LICENSE)。附带的第三方软件见 [NOTICE](NOTICE)。

感谢 [Pi](https://github.com/earendil-works/pi)（MIT，Mario Zechner）、[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（MIT，DeepSeek；按需安装到 Termux，不随应用附带）和 [Termux](https://termux.dev)。产品名称和标志归其各自所有者；小维助手与任何 AI 服务商均无关联。

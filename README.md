# WeiAssist · 小维助手

**An open-source AI assistant that operates your Android phone for you.**
Tell it what you want ("send an email to Aurora saying hello") and it opens the app, fills in the
details and asks you before it presses Send.

WeiAssist is built on **[Pi](https://github.com/earendil-works/pi)**, the minimal, extensible agent
harness. Pi handles models, sign-in and the agent loop; WeiAssist gives it eyes and hands on Android.

> **Status: early development.** It works end to end on real devices, for example sending an email in
> Gmail with a confirmation step. Expect rough edges. See the [roadmap](docs/ROADMAP.md).

## What it can do today

- **Sign in your way:** with your **ChatGPT subscription** (Pi's "Sign in with ChatGPT") or an **OpenAI API key**. Credentials stay on the phone, encrypted with the Android Keystore.
- **Pick a model and thinking level:** the list comes from Pi, filtered to what your sign-in can use.
- **Operate other apps:** read the screen, tap, type, scroll, press keys and open apps, through an accessibility service you turn on yourself.
- **Ask before anything irreversible:** Send, Pay and Delete need your OK on an on-screen card.
- **Keep working in the background:** tasks run in a foreground service. Stop is always in the notification, and the result arrives as a notification.
- **Stay out of your way:** it never touches WeiAssist itself, pauses while you look at it, and never sends password fields to the model.
- **English and Chinese UI.**

## Install

No release yet. Build from source (below), or download the debug APK from the latest CI run's artifacts.
Then open WeiAssist, sign in, and to let it operate apps, turn it on under **Settings → Accessibility**.

## Build

Requirements: Android SDK (platform 37), Node per [`.nvmrc`](.nvmrc) with npm, and a JDK (Gradle provisions its own).

```bash
./gradlew assembleDebug     # also bundles Pi (pi-host/) into the app
cd pi-host && npm test      # end-to-end tests of the bundled agent
./gradlew testDebugUnitTest lint
```

How it fits together: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Contributing: [CONTRIBUTING.md](CONTRIBUTING.md).

## Privacy

WeiAssist has no servers. When you give it a task, the text you type and the relevant screen
contents go to the AI provider you signed in with, and nowhere else. Password fields are never sent.
Your sign-in is stored only on your phone. See [SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE). Bundled third-party software and its licenses are listed in [NOTICE](NOTICE).
Pi is MIT-licensed by Mario Zechner. "ChatGPT" and "OpenAI" are trademarks of OpenAI; WeiAssist is
not affiliated with OpenAI.

---

## 中文简介

**小维助手**是一个开源的安卓 AI 助手：你说一句“给 Aurora 发封邮件说你好”，它就打开邮件应用、填好内容，并在点“发送”之前先问你。

- 用 **ChatGPT 订阅**或 **OpenAI API key** 登录，凭据只保存在手机上（系统密钥库加密）
- 模型和思考程度可选，列表来自 Pi
- 通过你亲手开启的无障碍服务操作其他应用；发送、付款、删除前一定先问你
- 任务在后台继续执行，通知栏随时可以“停止”，完成后用通知告诉你结果
- 不会操作小维助手自己，你在看它时自动暂停；密码框内容从不发给模型
- 界面支持中文和英文

底层的智能体框架是 [Pi](https://github.com/earendil-works/pi)，我们只用它公开的接口，不改它的代码，方便跟着上游升级。
构建方法见上文，参与开发请看 [CONTRIBUTING.md](CONTRIBUTING.md)。

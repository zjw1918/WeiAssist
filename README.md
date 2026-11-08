# WeiAssist 🐟 — Your AI assistant, on your Android phone

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: light)" srcset="docs/images/banner-light.png">
    <img src="docs/images/banner-dark.png" alt="WeiAssist (小维助手), an open-source AI agent for Android. AI engines: Pi and DeepSeek Harness. Linux tools: Termux.">
  </picture>
</p>

<p align="center">
  <a href="https://github.com/zjw1918/WeiAssist/releases/latest"><img src="https://img.shields.io/github/v/release/zjw1918/WeiAssist?style=flat-square&label=release" alt="Latest release"></a>
  <a href="https://github.com/zjw1918/WeiAssist/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/zjw1918/WeiAssist/ci.yml?branch=main&style=flat-square&label=ci" alt="CI status"></a>
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="License: Apache 2.0"></a>
</p>

<p align="center"><b>English</b> · <a href="README.zh-CN.md">简体中文</a></p>

WeiAssist is an open-source AI assistant that lives on your phone. Chat with the AI provider of your choice, hand
it photos and files, let it run Linux commands, use your phone's apps for you, and keep several conversations
going at once. Away from your phone? Talk to it from Telegram.

**You choose the engine.** The agent loop runs on [Pi](https://github.com/earendil-works/pi), built in, or on
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness), which the app installs for you. There are no
servers of ours in between: the app talks to your provider directly, and your keys stay on the phone.

**New: your phone as an AI gateway.** Install the optional Gateway app and the phone runs
[magpie](https://github.com/yetone/magpie): sign in once with your subscriptions and keys, and every engine, every app
on the phone and every device on your network can use them through one API. [More below](#ai-gateway-magpie).

[Download](https://github.com/zjw1918/WeiAssist/releases/latest) · [Termux setup](docs/TERMUX.md) ·
[Telegram](docs/TELEGRAM.md) · [AI gateway](#ai-gateway-magpie) · [Architecture](docs/ARCHITECTURE.md) · [Progress](docs/PROGRESS.md) ·
[Roadmap](docs/ROADMAP.md)

## Install

Download the APK from [Releases](https://github.com/zjw1918/WeiAssist/releases/latest) and open it. Allow
installs from your browser or file manager when Android asks.

Optional: **Gateway** (`Gateway-<version>.apk`, same release) makes the phone an AI gateway
for its own apps and the devices on its network ([AI gateway](#ai-gateway-magpie)). Skip it if you only want the
assistant.

## Quick start

1. Open WeiAssist and connect an AI provider: sign in with ChatGPT, SuperGrok or X Premium, or paste an API key.
   Or, with the Gateway app on and signed in, tap **Use Gateway**.
2. Start a conversation and ask something.
3. Optional, under **Settings**:
   - **Linux commands:** a guided [Termux](https://termux.dev) setup, so it can look things up, work with data and
     write and run code.
   - **AI engine:** keep Pi, or set up DeepSeek Harness (needs Termux, about 1.5 GB and 10–30 minutes).
   - **Phone tasks:** turn on its accessibility service to let it use apps.
   - **Telegram:** pair a bot to talk to it from anywhere ([guide](docs/TELEGRAM.md)).

## AI engines

| | **Pi** | **DeepSeek Harness** |
| --- | --- | --- |
| Setup | Built in | One tap in Settings → AI engine; installs Ubuntu, Node.js and dsh in Termux |
| What it brings | Minimal, fast agent loop | Its own files, bash, subagents, web fetch, skills and workflows |
| Version | Bundled | The tested version by default; install a newer or any version, and go back |

Both use the same providers, keys and sign-ins, and both get the app's phone tools and safety checks. Each
conversation keeps the engine it started with.

## AI gateway (magpie)

Gateway is a second, optional app that turns your phone into an AI gateway. It runs
[magpie](https://github.com/yetone/magpie) (built for Android, the whole gateway in one app): your providers,
subscriptions, routing and format translation in one place, on the phone.

- **One switch for every engine.** In WeiAssist, **AI providers → AI providers from** picks **WeiAssist** (the
  providers you set up in the app) or **Gateway**. With Gateway, Pi and DeepSeek Harness both send every request to
  it. Switch back any time; each side keeps its own provider and model.
- **Your subscriptions, everywhere.** Sign in once in the gateway's console (ChatGPT and more, or API keys): the
  assistant, the other apps on your phone and the devices on your network all use the same accounts.
- **Each model in its own format.** A model the gateway serves natively in Anthropic's Messages or OpenAI's
  Responses format is asked in that format, so thinking, caching and reasoning come through untranslated.
- **One API for your devices.** OpenAI-, Anthropic- and Gemini-compatible, at `http://<phone's IP>:3425/v1` on
  your network and `http://127.0.0.1:3425/v1` on the phone. Point any tool or computer at it.

To set it up:

1. Install `Gateway-<version>.apk` from the [same release](https://github.com/zjw1918/WeiAssist/releases/latest)
   and open it.
2. Turn it on. Allow its notification (it keeps it serving), and allow auto-launch in **App settings** so it starts
   again after a restart.
3. Tap **Open console** and sign in to your subscriptions or add keys. Devices on your network need a gateway key,
   made there too.
4. In WeiAssist, choose **AI providers → Gateway**.

WeiAssist works without it. If the gateway stops while it is chosen, the assistant says so and doesn't switch to
other providers by itself.

## Demos

### Subagents

<p align="center">
  <a href="docs/images/subagents-demo.mp4"><img src="docs/images/subagents-demo.gif" width="320"
    alt="A DeepSeek Harness session asked to compare Rust, Go and Zig with three subagents: each subagent works in its own live card with its time, steps and current step while the header counts how many are done; once all three are done the orchestrator's comparison table is the answer, and the Thinking and steps sheet shows each subagent's card with its report and its own work"></a>
</p>

With DeepSeek Harness, a task can run several subagents at once. Each one gets its own live card showing its
reasoning, steps, time and report. The answer comes once all of them are done: the orchestrator's summary, with the
rest of its work a tap away. [Watch the video](docs/images/subagents-demo.mp4) (sped up).

### Talk to it from Telegram

<p align="center">
  <img src="docs/images/telegram-demo.gif" width="560"
    alt="Telegram on a Mac and the phone side by side: asked for San Diego's weather, the assistant answers using Termux; asked to email the forecast with Gmail, it writes the email on the phone and asks before tapping Send, in Telegram and on the phone; Deny is tapped in Telegram and nothing is sent">
</p>

Create a bot with [BotFather](https://t.me/BotFather), paste its token in **Settings → Telegram** and pair your account. Each chat is a
conversation; it streams answers, takes photos and files, and asks you (✅ / ❌) before anything risky. No server
needed. Details: [docs/TELEGRAM.md](docs/TELEGRAM.md).

## What it can do

![Four WeiAssist screens: a DeepSeek Harness session with three subagents researching in parallel; its answer once they are done, one comparison table; Settings with the AI engine choice, Pi or DeepSeek Harness; and a Go web service it wrote, tested and left running in tmux](docs/images/showcase.png)

- **Many providers:** OpenAI, Anthropic, Google, DeepSeek, OpenRouter, xAI, Moonshot, Mistral, OpenCode, and any
  OpenAI-compatible server (Ollama, vLLM, LM Studio…).
- **An AI gateway on the phone:** with the Gateway app, your subscriptions and keys serve every engine, your other
  apps and your network through one API ([more](#ai-gateway-magpie)).
- **Photos and files:** send images, PDFs and documents; it sends back the files it makes.
- **Linux on the phone:** with Termux it browses, analyzes data, and builds and runs code, with long jobs kept
  running in `tmux`.
- **Several conversations at once,** each in the background with its own history, plus a supervisor that starts,
  checks and stops them for you, by typing or by voice.
- **Phone tasks:** it can use your apps, and asks before anything like Send, Pay or Delete.
- **English and Chinese UI.**

## Privacy and safety

- WeiAssist has no servers. Your messages, attachments, command output and, for phone tasks, the relevant screen
  go only to the AI provider you chose. Password fields are never sent.
- Sign-ins are stored only on the phone, encrypted with the Android Keystore. DeepSeek Harness reaches your
  provider through the app and never holds a key.
- Before anything like Send, Pay or Delete, it asks you, whichever engine is running. Stop works at any time.
- The Gateway app keeps its own sign-ins, in its own app data; WeiAssist never copies its keys there. Any app on
  the phone can use the gateway without a key; other devices need a gateway key.

More in [SECURITY.md](SECURITY.md).

## Documentation

| Goal | Start here |
| --- | --- |
| Set up Linux commands | [docs/TERMUX.md](docs/TERMUX.md) |
| Talk to it from Telegram | [docs/TELEGRAM.md](docs/TELEGRAM.md) |
| Use the AI gateway | [AI gateway](#ai-gateway-magpie) · [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#where-the-ai-providers-come-from) |
| Understand how it fits together | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| See what's done and what's next | [docs/PROGRESS.md](docs/PROGRESS.md) · [docs/ROADMAP.md](docs/ROADMAP.md) |
| Contribute | [CONTRIBUTING.md](CONTRIBUTING.md) |

## Development

Requirements: Android SDK (platform 37), Node per [`.nvmrc`](.nvmrc) with npm, and a JDK.

```bash
git submodule update --init      # magpie/upstream: the gateway app's source (needs Go to build)
./gradlew assembleDebug          # bundles Pi (pi-host/) and the DeepSeek Harness bridge (dsh-host/); builds both apps
./gradlew testDebugUnitTest lint
cd pi-host && npm test           # the bundled agent, end to end
cd dsh-host && npm test          # the dsh bridge; set DSH_RUN and DSH_HOME to run it against a real dsh
```

Releases are built and signed by the Release workflow when a version tag is pushed
(`git tag v0.2 && git push origin v0.2`). Locally, `assembleRelease` signs only when it finds a
`keystore.properties` (at `WEIASSIST_SIGNING`, or in `~/.android/weiassist-release/`).

## License

[Apache License 2.0](LICENSE). Bundled third-party software is listed in [NOTICE](NOTICE).

Thanks to [Pi](https://github.com/earendil-works/pi) (MIT, Mario Zechner), [magpie](https://github.com/yetone/magpie)
(MIT, yetone; in the Gateway app),
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (MIT, DeepSeek; installed in Termux on request,
not bundled) and [Termux](https://termux.dev). Product names and logos are trademarks of their owners; WeiAssist is
not affiliated with any AI provider.

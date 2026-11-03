![WeiAssist: an open-source AI agent for Android, built on Pi and Termux](docs/images/banner.png)

# WeiAssist · 小维助手

**An open-source AI assistant for Android, built on [Pi](https://github.com/earendil-works/pi).**
Chat with the AI provider of your choice, hand it files and photos, let it run Linux commands for live
information and coding, and keep several conversations going at once. No servers of ours in between:
the app talks to your provider directly.

Pi, the minimal, extensible agent harness, handles models, sign-in and the agent loop; WeiAssist brings it to
the phone with a native app around it.

> **Status: early development.** It works on real devices (Android 12 to 16), but expect rough edges.
> What's done and what's next: [PROGRESS.md](docs/PROGRESS.md) and the [roadmap](docs/ROADMAP.md).

## Features

![Four conversations in WeiAssist: live weather with curl, a Go web service with tests running in tmux, a year of Hong Kong stock data with a live watch job, and a narrated video made from an idea](docs/images/showcase.png)

- **Many AI providers:** OpenAI (sign in with your ChatGPT subscription, or an API key), Anthropic, Google,
  DeepSeek, OpenRouter, xAI, Moonshot, Mistral and OpenCode, through Pi.
- **Your own models:** add any OpenAI-compatible server, such as Ollama, vLLM, LM Studio or a relay. It fetches
  the model list and detects each model's context size, which you can correct.
- **Model and thinking level:** pick per provider, from what your sign-in can use.
- **Attachments:** send photos, PDFs and text or code files with a message. Images open in a full-screen viewer.
- **Linux commands (optional):** with [Termux](https://termux.dev) installed, the assistant can look things up on
  the web, work with data, and write, build and run code, with long jobs kept running in `tmux`.
  Setup: [docs/TERMUX.md](docs/TERMUX.md).
- **Several conversations at once:** each runs on its own and keeps going in the background, with saved
  history. Send a message while one is working and it reads it before its next step. A supervisor
  conversation can start, check and stop the others for you, by typing or by voice.
- **Phone tasks (optional):** if you turn on its accessibility service, it can also use apps on the phone for
  a task, and asks you before anything like Send, Pay or Delete.
- **Private by design:** sign-ins are stored only on the phone, encrypted with the Android Keystore. Password
  fields are never sent to a model.
- **English and Chinese UI.**

<p align="center">
  <img src="docs/images/agent-made-video.jpg" width="640"
    alt="A frame from a narrated explainer video about how tuna breathe, with subtitles">
  <br>
  <sub>A frame from the video in the last example: from one idea, the assistant wrote the script, generated the
  voice-over, drew and rendered the animation in Termux, all on the phone.</sub>
</p>

## Install

Download the APK from [Releases](https://github.com/zjw1918/WeiAssist/releases/latest) and install it (allow installs
from your browser or file manager when Android asks). You can also build from source (below).
Then open WeiAssist and connect it to an AI provider. Optional extras, both under **Settings**:
Linux commands (set up Termux as described in [docs/TERMUX.md](docs/TERMUX.md)) and phone tasks (accessibility).

## Build

Requirements: Android SDK (platform 37), Node per [`.nvmrc`](.nvmrc) with npm, and a JDK (Gradle provisions its own).

```bash
./gradlew assembleDebug     # also bundles Pi (pi-host/) into the app
cd pi-host && npm test      # end-to-end tests of the bundled agent
./gradlew testDebugUnitTest lint
```

Maintainers publish a release by pushing a version tag (`git tag v0.2 && git push origin v0.2`): the Release workflow
builds, signs and attaches the APK. Locally, `assembleRelease` signs the APK only when it finds a key: a `keystore.properties` file (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`) at the path in `WEIASSIST_SIGNING`, or in `~/.android/weiassist-release/`. Without one the
release APK is unsigned.

How it fits together: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Contributing: [CONTRIBUTING.md](CONTRIBUTING.md).

## Privacy

WeiAssist has no servers. Your messages and attachments, the output of commands it runs, and, for phone
tasks, the relevant screen contents go to the AI provider you chose, and nowhere else. Password fields are
never sent. Your sign-ins are stored only on your phone. See [SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE). Bundled third-party software and its licenses are listed in [NOTICE](NOTICE).
Pi is MIT-licensed by Mario Zechner. Product names such as ChatGPT, OpenAI, Claude and Gemini are trademarks of
their owners; WeiAssist is not affiliated with any AI provider.


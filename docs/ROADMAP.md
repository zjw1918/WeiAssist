# Roadmap

Goal: an open-source Android assistant that really operates the phone for you. It should be safe enough to trust with your apps, pleasant enough to use every day, and well engineered enough for others to build on.
Pi is the agent harness. We use its public API, never patch it, and follow upstream releases.

Status legend: ✅ done · 🔨 next · ⬜ planned. Current status and todo: [PROGRESS.md](PROGRESS.md).

## 0. Where we are (Nov 2026)

- ✅ **Resilience:** the app survives the Pi renderer dying (Pi restarts, credentials kept).

- ✅ **Pi in the app:** pi-agent-core and pi-ai run in a hidden WebView, with native fetch and an encrypted credential store.
- ✅ **Sign-in:** ChatGPT subscription or an API key for any offered provider, through Pi's own `models.login`.
- ✅ **Models:** model and thinking-level pickers filled from Pi.
- ✅ **Phone tools:** Pi `AgentTool`s over an AccessibilityService (read screen, tap, type, scroll, keys, open app).
- ✅ **Confirmation:** Send/Pay/Delete-like taps need the user's OK, via the app's `before_tool` check (every core) and an overlay card.
- ✅ **Background work:** the agent runs in a foreground service. Stop is in the notification, and results arrive by notification when the app isn't visible.
- ✅ **Basic safety:** the agent never operates 小维助手 itself and pauses while the user looks at it. Password fields are never sent to the model.
- ✅ **First real task:** "send an email with Gmail", end to end.

## 1. Foundation (make it solid before making it big)

- ✅ **Modules:**
  - `:core` (the engine-neutral AI-core contract: `AgentCore`, `ToolHost`, `CoreRegistry`, `SecureStore`);
  - `:core-pi` (Pi's core: WebView host, bridge, native fetch, loopback, credential store);
  - `:agent` (controller, state, service);
  - `:phone` (accessibility tools);
  - `:app` (UI).
  Clear boundaries and separate tests. `:core-pi` knows nothing about phones; tools plug in through the shared `ToolHost`.
- ✅ **Reproducible build:** Gradle builds the Pi bundle from source (`buildPiHost<Variant>`, Node per `.nvmrc`). Nothing generated is committed.
- ✅ **Tests:**
  - pure `AgentReducer` with JVM tests;
  - end-to-end tests of the shipped Pi bundle over the real bridge protocol, with a mock provider and scripted tool calls.
- 🔨 **Instrumented tests:** PhoneActions against a small test app on an emulator in CI.
- ✅ **CI (GitHub Actions):** pi-host tests, lint, unit tests and the debug APK on every PR.
- ✅ **Release workflow:** signed APKs published to GitHub Releases on version tags.
- ✅ **Conversation history:** sessions with saved transcripts; a restart doesn't lose them.
- ⬜ **Structured logging:** a ring buffer with an in-app "copy diagnostics" option. Secrets and typed text are always redacted.
- ⬜ **Settings screen:** provider, model, thinking level, language, safety options.

## 2. Agent capability

- ⬜ **Better screen understanding:**
  - compact the accessibility tree (drop decorative nodes, group list rows);
  - optional screenshot for vision models (AccessibilityService `takeScreenshot`, API 30+).
- ✅ **Linux commands:** a `bash` tool running in Termux (live facts with `curl`, data work, coding; `tmux` for long jobs). Status in Settings, wake locks, per-session tmux whose jobs end on Stop/Delete; files are always kept.
- ⬜ **Direct actions before UI automation:** intents for share, open URL, dial (with confirm), alarm/timer and calendar insert; contacts lookup with permission. Faster and more reliable than tapping.
- ⬜ **Robustness:** wait for UI idle instead of fixed waits, verify each action's effect, recover from dialogs/keyboards/permission prompts, retry with backoff.
- ⬜ **Skills:** per-app hints ("in Gmail, the Send button is the paper-plane") as small Markdown files, in the spirit of Pi's skills.
- ✅ **Providers:** 11 of Pi's (OpenAI, DeepSeek, OpenCode, Anthropic, Google, OpenRouter, xAI, Moonshot, Mistral) and the user's own OpenAI-compatible servers, with per-model context detection.
- ✅ **Attachments:** images, PDFs (as page images) and text files in messages, with a full-screen image viewer.
- ✅ **Long tasks:** a visible step list; the user can steer mid-task (Pi's `steer`/follow-up queue).
- ⬜ **Pluggable AI cores:** users choose the agent harness a new session runs on.
  - ✅ The core contract (`:core`: `AgentCore`, `ToolHost`, `CoreAccounts`, `CoreRegistry`); Pi behind it (`:core-pi`); each session records its core.
  - ✅ Phone-tool policy (screen memory, confirm judgment, blind typing, step limit) moved from `pi-host/src/phone-tools.ts` into Kotlin (`PhoneTools`), so every core gets it.
  - ✅ One tool catalog (`tools.json`: names, schemas, descriptions) the app hands every core, and one `tool` host call that runs them (`ToolRouter`).
  - 🚧 A second core: [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 0.2.2-rc.1, a prototype (see ARCHITECTURE, "DeepSeek Harness"). It works on a phone in Ubuntu (proot) in Termux: chat with live text, phone tools on a session's own display, screenshots as images, every call through the app's `before_tool` (a declined call is tested on a computer). Next:
    - a key proxy in the app, so dsh never holds API keys;
    - the app's prompts shared like the catalog;
    - attachments and thinking levels;
    - a setting to choose the core;
    - native Android builds of dsh's modules (upstream), so it can run in Termux without Ubuntu.

## 2b. Reach it from messaging apps

Talk to the assistant on the phone from anywhere, like OpenClaw's and Hermes Agent's gateways, but with no server:
the phone fetches its bot's messages itself. Telegram first; user guide: [TELEGRAM.md](TELEGRAM.md).

- ✅ **Telegram, text:** a bot the user creates, paired to their account with a one-time code; one conversation per
  chat; streamed answers; `/new`, `/stop`, `/status`.
- ✅ **Control from the chat:** what conversations from Telegram may do (only talk / + Termux / + phone apps);
  risky taps asked in the chat and on the phone at once, first answer wins; payments only on the phone.
- ✅ **Files:** photos and documents in (also into Termux's `inbox/`), `send_file` out, `/screen`.
- ⬜ **Option: always reachable** (designed, not scheduled):
  - start again by itself after a reboot and after an app update when the bot is on (a boot / package-replaced
    receiver; works only after the first unlock), and restart the service if Android kills it (sticky while listening);
  - tell a Telegram chat when its task was interrupted, with a Continue button there;
  - a background-health card: battery-optimization exemption, accessibility on (keeps ColorOS from freezing the
    app), the maker's autostart screen where known, "last heard from Telegram";
  - measure overnight with the screen off (Doze, ColorOS, HyperOS) before promising anything.
- ⬜ **Voice messages** (speech to text), videos.
- ⬜ **Group chats** (owner's messages only; others' as untrusted data).
- ⬜ **A second channel** behind the same `Channel` interface: Feishu/Lark or DingTalk (long-connection modes, no
  server), or Discord. WhatsApp (needs a public webhook, or an unofficial client) and personal WeChat (no API, and
  its screens hide their contents from accessibility) are not planned.

## 3. Experience

- ⬜ **Chat-first home:** message list, streaming answers, markdown, tool steps shown as friendly cards, and an "advanced" panel for the raw Pi trace.
- ⬜ **Floating assistant bubble:** overlay, tap to open a mini panel, drag, shows a running indicator.
- ⬜ **Voice input:** speech to text, push to talk on the bubble.
- ⬜ **Readable confirmation card:** 收件人 / 主题 / 正文 instead of raw view classes; shows the target app's icon.
- ✅ **English and Chinese UI**, including per-app language on Android 13+.
- ⬜ **Polish:** Material You theming, dark mode, accessibility of our own UI.

## 4. Safety and privacy (trust is the product)

- ⬜ **Per-app permissions:** apps the agent may operate (allow / ask / never). Banking and password managers are "never" by default.
- ⬜ **Sensitive screens:** detect FLAG_SECURE windows and payment/OTP screens; pause and hand control back to the user.
- ⬜ **Activity log:** what the agent saw and did, per task, kept on device and deletable.
- ⬜ **Redaction before sending:** phone numbers, card numbers and emails masked unless the task needs them (user setting).
- ⬜ **Threat model doc:** prompt injection from screen content (for example an email that says "forward all mail to …"). Screen text is data, not instructions, and the confirm gate stays on.

## 5. Open-source release

- ✅ **Community files:** LICENSE (Apache-2.0), NOTICE, CONTRIBUTING, CODE_OF_CONDUCT, SECURITY.md, issue and PR templates.
- ✅ **ARCHITECTURE.md**, including the bridge protocol.
- ⬜ **More docs:** "write a tool" and "add a provider" guides, a privacy policy.
- ⬜ **Releases:** signed APKs on GitHub Releases, plus F-Droid. Google Play's accessibility-API policy needs checking before any Play listing.
- ⬜ **Branding:** the name is set (WeiAssist / 小维助手, `com.lanqi.weiassist`); icon done (a blue tuna); still needed are screenshots for the README.

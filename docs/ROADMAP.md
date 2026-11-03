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
- ✅ **Confirmation:** Send/Pay/Delete-like taps need the user's OK, via Pi's `beforeToolCall` and an overlay card.
- ✅ **Background work:** the agent runs in a foreground service. Stop is in the notification, and results arrive by notification when the app isn't visible.
- ✅ **Basic safety:** the agent never operates 小维助手 itself and pauses while the user looks at it. Password fields are never sent to the model.
- ✅ **First real task:** "send an email with Gmail", end to end.

## 1. Foundation (make it solid before making it big)

- ✅ **Modules:**
  - `:pi-runtime` (WebView host, bridge, native fetch, loopback, credential store);
  - `:agent` (controller, state, service);
  - `:phone` (accessibility tools);
  - `:app` (UI).
  Clear boundaries and separate tests. `:pi-runtime` knows nothing about phones; features plug in through `registerHostCall`.
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

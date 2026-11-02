# Progress

Status of WeiAssist (小维助手). Updated as work lands. For the long-term plan see [ROADMAP.md](ROADMAP.md).

_Last updated: 2026-11-01_

## Done

### Foundation
- **Pi on Android:** `pi-agent-core` and `pi-ai` 1.4.0 run in a hidden WebView, using Pi's public exports only.
  - native fetch (no CORS);
  - loopback `node:http` for OAuth;
  - Keystore-encrypted credential store;
  - renderer-crash recovery.
- **Sign-in:** ChatGPT subscription (Pi's "Sign in with ChatGPT") or OpenAI API key, through Pi's `models.login`.
  On realme/ColorOS, the user taps ✕ in Chrome to return; the frozen app then answers the queued callback.
- **Model and thinking-level pickers:** filled from Pi (`OPENAI_CODEX_MODELS` filter for ChatGPT accounts, `getSupportedThinkingLevels`).
- **AgentService:** a foreground service (`specialUse`) while a task or sign-in runs.
  - Stop in the notification;
  - a result notification when the app isn't visible;
  - tasks survive closing the app.
- **Modules:** `:pi-runtime` (generic `registerHostCall`) · `:phone` · `:agent` · `:app`; package `com.lanqi.weiassist`.
- **Build:** Gradle builds the Pi bundle (`buildPiHost<Variant>`); no generated files are committed.
- **i18n:** English (default) and Chinese, with per-app language on Android 13+.
- **Tests:**
  - 16 JVM tests (`AgentReducer`);
  - 11 end-to-end tests of the shipped bundle (`pi-host/test`) over the real bridge protocol, with a mock provider and scripted tool calls.
- **Project files:** CI workflow, README (en and zh), ARCHITECTURE, CONTRIBUTING, Code of Conduct, SECURITY, Apache-2.0 and NOTICE.

### Agent capabilities
- **Tools:** `open_app`, `read_screen` (with element centres), `screenshot` (image for vision models), `tap`, `tap_at`, `long_press`, `type_text`, `scroll`, `swipe`, `press_key`, `wait`.
- **Settling:** every action waits for the UI to settle.
- **Context:** Pi's `transformContext` keeps only the latest two screens per model call.
- **Safety:**
  - taps on Send/Post/Reply/Pay/Buy/Order/Delete-like labels need the user's OK, on an overlay card worded in the user's language, including coordinate taps;
  - 120-step limit per task;
  - the agent never operates WeiAssist itself, and pauses while the user looks at it;
  - password fields are never sent to the model;
  - screen content is treated as data, not instructions.
- **System prompt for multi-step work:** ask when a preference matters, dismiss pop-ups, recover from wrong turns, stop at logins and captchas, never invent facts.

### Verified on a real device (realme RMX2121, Android 12)
- ChatGPT subscription sign-in, then "你好" through Pi (`gpt-6.1-sol`).
- Gmail: sent an email to two different addresses, each after the confirmation card.
- Settings: read the Android version with the app closed; the result arrived as a notification.
- Stop, pause and resume, and renderer-crash recovery.

## Next: real-world scenarios on the device

Blocked on the user, because the app was reinstalled under the new package:
- [ ] Sign in to WeiAssist again (ChatGPT).
- [ ] Turn on WeiAssist under Settings → Accessibility.
- [ ] Decide whether to install Bilibili / Xiaohongshu / Pinduoduo (not installed now).
- [ ] Be signed in to Taobao / JD for the shopping test.

Scenarios to build, test and refine (installed apps first):
- [ ] **Social browsing, Douban:** browse a topic, collect the top items with ratings, summarise.
- [ ] **Social browsing, Bilibili / Xiaohongshu:** if installed. Navigate, scroll the feed, extract posts, do a user-directed action.
- [ ] **Shopping assistant, Taobao + JD:**
  - search a product and compare price, specs, reviews and seller;
  - recommend one and add it to the cart;
  - ordering waits for the user's explicit approval (the confirmation card).
- [ ] **Daily productivity, Gmail (and Outlook):** check new mail, summarise the important ones, extract action items, build a prioritised to-do list for the day.
- [ ] For each scenario, log failures (wrong taps, missed content, loops, pop-ups) and fix them in tools, prompt or recovery logic.

## Known gaps and todo

- [ ] **Readable confirmation card:** show the target app and a summary (recipient, item and price) instead of raw field lines.
- [ ] **Chat-style home screen:** message history, markdown rendering, friendly step cards. The raw Pi trace goes in an "advanced" view.
- [ ] **Conversation history** persisted across restarts (Pi transcript in Room).
- [ ] **Ask-the-user tool**, for mid-task questions (preferences, choosing between options) without ending the task.
- [ ] **Direct intents** where they beat UI automation: share, open URL, alarm, calendar.
- [ ] **Per-app permissions** (allow / ask / never) and sensitive-screen detection (FLAG_SECURE, payment, OTP).
- [ ] **Floating assistant bubble** and voice input.
- [ ] **More providers:** register Pi's other providers (Anthropic, Google, DeepSeek, OpenRouter, local).
- [ ] **Release workflow:** signed builds on tags. Not yet on GitHub: the user still has to pick the org and repo name.
- [ ] **Instrumented tests** for PhoneActions on an emulator.
- [ ] **Repo folder:** the local folder is still `PhoneAgent`; rename it if wanted.

## Working notes

- Set a task on the device (debug build): `adb shell "am start -n com.lanqi.weiassist/.MainActivity --es task '…'"`.
- Never `am force-stop` the app on realme: that disables its accessibility service.
- UI dumps of our own Compose screen can be stale; use screenshots to locate buttons.
- Mock provider: `node tools/mock-openai.mjs` with `adb reverse tcp:8787 tcp:8787`, then the dev switch in the app.

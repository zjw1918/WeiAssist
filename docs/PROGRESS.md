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

### Multi-session (2026-11-01)
- **Sessions:** each conversation is its own Pi Agent in the one runtime; sessions run at the same time and
  keep running when their page is closed. Session list (running first) and session page (chat, tool steps,
  Pi events, follow-up messages).
- **Controls:** start, pause, resume, stop, stop all, continue, rename, delete.
- **History:** sessions saved per file (summary, chat, Pi transcript), restored after restarts, process death
  (*Interrupted* → Continue) and Pi renderer crashes.
- **One screen for now:** phone sessions queue for the phone's screen; chat sessions never wait.
- **Notifications:** one "working" notification (count, Stop all); a result notification per session that opens it.
- **Tests:** pi-host 17 (sessions in parallel, restore, pause gate, transcript pruning); JVM 23 (SessionReducer, storage format).
- **Spike, virtual displays (shell uid, this realme):** an untrusted public virtual display works; third-party apps
  launch on it (`am start --display`); accessibility sees its windows; `input -d` taps and types. Trusted
  displays are refused on Android 12. ColorOS denies shell `WRITE_SECURE_SETTINGS` unless "Disable permission monitoring" is on.

### Built-in shell backend (2026-11-01)
- **`:shell` module:** pairing with Wireless debugging on the phone itself (code typed into a notification),
  adb over TLS to the phone's own adbd, and a helper process running as shell (`ShellServer`) whose Binder
  the app holds. Settings → Shell backend: Set up, Start, Stop, Test, Forget pairing.
- **Verified on the realme:** paired on the first try (listed as `weiassist@RMX2121`); adbd matched our
  key; the helper started through shell v2 and attached in under a second as uid 2000; it re-attached after
  the app's process was killed; it replaced itself after an app update; `Test` ran `id` as shell.
- **Learned on the device:** Android's provider restores a PKCS#8 RSA key as a non-CRT key (a cast failed,
  so a new key was made each time); the legacy `shell:` service doesn't start the helper, shell v2 does;
  this ROM announces no `_adb-tls-connect` mDNS service and doesn't expose `service.adb.tls.port`, so the
  connect port is found by a loopback scan (about 10 s, then cached); ColorOS refuses `pm grant` from shell.
- **Tests:** JVM 5 (HKDF RFC 5869 vector, GCM counters, adb public-key layout, certificate, adb framing).

### A virtual display per phone session (2026-11-01)
- Phone sessions get their own virtual display from the shell helper (3 at most, kept 3 minutes after a task);
  without the helper they still take turns on the phone's screen.
- `open_app` guards: an app another running session uses is refused; so is a single-task app open in front of
  the user; after launching, the app must be on the session's display.
- **Verified on the realme:** two mock sessions in Settings and Douban at the same time on displays 8 and 9,
  each reading its own screen, screenshots correct, the user's screen unchanged; two sessions wanting Settings:
  the second was refused; the idle display was taken back when a fourth was needed. Real model, at the same time:
  "打开设置，查看电池电量是多少" (→ 100%) and "在 Gmail 里搜索 invoice…" (typed into Gmail's search on its own
  display; no keyboard on the user's screen) — both answered.
- **Learned on the device:** `ActivityThread.systemMain()` must run on the helper's main thread; an exception
  type Binder can't carry reached the app as a silent 0 (now converted to IllegalStateException); `Settings`
  is single-task, so a second launch moves it between displays.
- **Tests:** JVM 3 more (DisplayTasks).

### Verified on a real device (realme RMX2121, Android 12)
- ChatGPT subscription sign-in, then "你好" through Pi (`gpt-6.1-sol`).
- Gmail: sent an email to two different addresses, each after the confirmation card.
- Settings: read the Android version with the app closed; the result arrived as a notification.
- Stop, pause and resume, and renderer-crash recovery.
- Sessions (mock model): a phone task and a chat at once; a second phone task queued and started 59 ms after the
  first ended; pause held every action until resume; a killed process → Interrupted → Continue resumed the
  scripted task at its third step; after a renderer crash the next message carried the whole conversation.
- Sessions (real model): "打开设置，告诉我安卓版本号" and a chat question at the same time; both answered.

## Next: multi-session, steps 2–5

- [x] **2. Built-in shell backend:** pairing, adb over TLS, shell helper with Binder link. Not yet: tested across a reboot.
- [x] **3. A virtual display per phone session:** create/launch/input through the helper; read screens per display through accessibility.
- [ ] **4. Live previews:** the display's Surface rendered in the session page and the list; adaptive size and frame rate; nothing rendered when hidden.
- [ ] **5. Supervisor session:** a session whose tools manage the others (create, start, pause, resume, stop, delete, message); voice-ready.

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
  Add `--ez run true` to start it in a new session, `--ez phone false` for a chat, `--ez mock true` for the mock model.
- Debug: `--ez crashPi true` crashes Pi's renderer; `--ez killProcess true` ends the process (session recovery).
- Never `am force-stop` the app on realme: that disables its accessibility service.
- UI dumps of our own Compose screen can be stale; use screenshots to locate buttons.
- Mock provider: `node tools/mock-openai.mjs` with `adb reverse tcp:8787 tcp:8787`, then the dev switch in the app.

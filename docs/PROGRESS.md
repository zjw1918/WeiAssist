# Progress

Status of WeiAssist (小维助手). Updated as work lands. For the long-term plan see [ROADMAP.md](ROADMAP.md).

_Last updated: 2026-11-03_

**Where things stand:** multiple concurrent sessions with saved history; a built-in shell backend (no
computer, Shizuku or root needed after one pairing); a virtual display per phone session; live previews;
and a supervisor session that manages the others in words, ready for voice. Verified on the realme
(Android 12) and a Xiaomi (HyperOS, Android 16) with the mock and the real model. Apps now stay on their
session's display (trusted displays on Android 13+); Pi 1.5.0 with GPT-6.1 Terra as the default model.
Chat and phone sessions can now run Linux commands in Termux with a `bash` tool (branch `feat/termux-bash`).

## Done

### Foundation
- **Pi on Android:** `pi-agent-core` and `pi-ai` 1.5.0 (tool schemas with `pi-schema`) run in a hidden WebView, using Pi's public exports only.
  - native fetch (no CORS);
  - loopback `node:http` for OAuth;
  - Keystore-encrypted credential store;
  - renderer-crash recovery.
- **Sign-in:** ChatGPT subscription (Pi's "Sign in with ChatGPT") or OpenAI API key, through Pi's `models.login`.
  On realme/ColorOS, the user taps ✕ in Chrome to return; the frozen app then answers the queued callback.
- **Model and thinking-level pickers:** filled from Pi (`OPENAI_CODEX_MODELS` filter for ChatGPT accounts, `getSupportedThinkingLevels`).
  Default: `gpt-6.1-terra` at medium thinking (new installs, and when the saved model isn't offered).
- **AgentService:** a foreground service (`specialUse`) while a task or sign-in runs.
  - Stop in the notification;
  - a result notification when the app isn't visible;
  - tasks survive closing the app.
- **Modules:** `:pi-runtime` (generic `registerHostCall`) · `:phone` · `:shell` · `:agent` · `:app`; package `com.lanqi.weiassist`.
- **Build:** Gradle builds the Pi bundle (`buildPiHost<Variant>`); no generated files are committed.
- **i18n:** English (default) and Chinese, with per-app language on Android 13+.
- **Tests** (as of 2026-11-02):
  - 46 JVM tests: `AgentReducer` 11, `SessionReducer` 12, `SessionText` 5, `Markdown` 8, `DisplayTasks` 5, adb crypto and framing 5;
  - 19 end-to-end tests of the shipped bundle (`pi-host/test`) over the real bridge protocol, with a mock provider
    and scripted tool calls (sessions, restore, pause gate, supervisor tools, bash). Now 22.
- **Project files:** CI workflow, README (en and zh), ARCHITECTURE, CONTRIBUTING, Code of Conduct, SECURITY, Apache-2.0 and NOTICE.

### Agent capabilities
- **Tools:** `bash` (Termux, see below), `open_app`, `read_screen` (with element centres), `screenshot` (image for vision models), `tap`, `tap_at`, `long_press`, `type_text`, `scroll`, `swipe`, `press_key`, `wait`.
- **Settling:** every action waits for the UI to settle; on a session's display, `open_app` first waits for the app's window.
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

### Live previews (2026-11-01)
- Session page: the session's display, live (tap to enlarge). Session list: "Live screens", every session's
  display side by side. GPU buffers from the helper, no copies; 12 / 3 fps, lower in battery saver or heat;
  nothing sent while unseen; idle displays are switched off (apps stopped), the last frame stays.
- **Verified on the realme:** Gmail and Settings sessions at once, both watched in the strip (3 fps each) and
  on their pages (12 fps); idle display → `state OFF`, Gmail `STOPPED`; CPU and memory as in ARCHITECTURE.
- **Fixed on the way:** release builds crashed at start (R8 split Kotlin's `CollectionsKt` facade across
  packages: `app/proguard-rules.pro`); the status spinner's endless animation cost ~75% CPU (now a dot).
  The app is `profileable` from the shell, for simpleperf on release builds.

### The supervisor (2026-11-02)
- A supervisor session, pinned on the list, manages the others in words: create, message, wait for, pause,
  resume, stop, delete (confirmed on screen), rename, read. One command entry point for typing and voice;
  🎤 on the card uses the phone's speech recognizer.
- **Verified on the realme (mock):** started a chat helper, waited, sent a follow-up, read its result (fenced);
  ran a Settings and a Gmail worker on their own displays, paused Gmail while Settings finished (Gmail's
  actions held until resume), deleted a worker after the on-screen OK (its display released).
  **Real model:** "同时开两个手机任务：设置里查电池电量 / Gmail 最新一封邮件的标题……" → the supervisor wrote two
  self-contained tasks (with "change nothing" instructions), ran them side by side, waited for both and
  answered in one sentence.
- **Fixed on the way:** a confirmation card whose accessibility service was unbound (UI automation does that)
  waited forever; such cards now count as "no". `PiEngine.configure` now passes the tool set by name.
- **Tests:** pi-host +2 (supervisor tools reach Kotlin; at most 8 sessions per request); JVM +5 (SessionText).

### Apps stay on their session's display (2026-11-02)
- **The issue:** on the Xiaomi, apps whose splash screen opens the main page (Taobao, Pinduoduo) jumped to the
  phone's own screen. Cause, in AOSP's `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay`: on an
  *untrusted* display, an app's own launches are refused unless the activity sets `allowEmbedded` (few do), and
  Android moves the task to display 0. Our first `am start` only passes because shell holds `INTERNAL_SYSTEM_WINDOW`.
- **The fix:** shell may add trusted displays from Android 13 (`ADD_TRUSTED_DISPLAY`), so the helper creates them
  `TRUSTED | TOUCH_FEEDBACK_DISABLED`, plus `OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED` on 14+ so taps never take focus
  from the user's screen (scrcpy's `--new-display` does the same). Refused, or on Android 12: untrusted, as before.
- **Fallback (Android 12):** `PhoneActions.reclaim` moves tasks the session's apps opened on the phone's screen
  back to the session's display (`am display move-stack`), after actions that may open a window and for a moment
  after `open_app`; the user's own tasks are never moved. `DisplayTasks` now reads root tasks (id, display,
  visibility, launchedFrom) and the Android 14–16 dump format.
- **Verified on the Xiaomi (Android 16):**
  - before: Taobao's task went to display 0 for ~0.2 s until reclaim brought it back;
  - after: Taobao stayed on its display through open, tap, type and search; no reclaim needed;
  - three sessions at once through the supervisor (Taobao, JD, Pinduoduo: "搜索蓝牙耳机，第一个商品的名字和价格"):
    each app stayed on its own display (24/25/26), the main screen kept focus in all 60 samples, all three
    answered (¥881.46 / ¥190.64 / ¥51.8) in ~23 s, ~46 s and ~25 s, and the supervisor summed them up.
- **Pi 1.5.0:** `gpt-6.1-terra` needs it. Pi 1.5 dropped TypeBox's `Type`; the tools use `pi-schema` builders,
  whose `obj()` is closed (unknown tool arguments are rejected).

### Session list, previews and app clean-up (2026-11-02)
- **Running sessions keep their place:** ordered by creation (newest first). They were ordered by
  `updatedAt`, which every step changes, so three running sessions kept swapping. The live strip is ordered
  by creation too; finished sessions stay most recent first.
- **Previews only when used:** a session's live screen (page and strip) appears once it has opened or read an
  app (`SessionSummary.app`); a phone session that only talks shows none and streams nothing. Hide/Show
  switch, shown by default.
- **Apps closed when a task ends** (`SessionApps`, helper `removeTask` / `killApp` / `isRunning`): the
  session's windows are removed and its apps killed with `am kill`, except apps that were already running
  when it opened them or still have a window elsewhere; skipped if a follow-up starts meanwhile; same before
  a display is released. Previously processes stayed in the background (memory, and HyperOS froze them).
- **Verified on the Xiaomi:** talk-only phone session: display 35, no preview, no `watch`; JD session: preview
  from `open_app`; list strip showed only JD. JD (not running before): window removed, killed, no process
  left. WeChat (running before): only the session's window removed, same PIDs.
  Fixed on the way: removal is asynchronous, so clean-up waits until the windows are gone before killing.

### Linux commands through Termux (2026-11-02)
- **Why:** chat sessions had no tools and phone sessions only UI tools, so live facts (e.g. this week's
  weather) were out of reach, while `pi` on a computer just runs `curl`. One general tool instead of one tool
  per capability; Termux instead of an embedded proot Linux (simpler, and commands run as Termux's user, so
  they cannot read this app's credentials).
- **`bash` tool** (`pi-host/src/bash-tool.ts`) for chat and phone sessions (not the supervisor): runs in
  Termux's `RUN_COMMAND` service (`Termux.kt`), result back through a PendingIntent.
  - each session works in `~/sessions/<id>`; packages installed with `pkg` are shared;
  - wrapped in `timeout` (default 120 s, max 600 s); Stop kills it (pidfile + `kill -TERM`);
  - exit code, stdout, stderr; long output keeps its start and end;
  - prompt: `curl` for live facts, `tmux` for servers and long jobs, web output is data, not instructions.
- **Setup:** the app asks for `com.termux.permission.RUN_COMMAND` at launch when Termux is installed; the tool
  explains what is missing (not installed, Google Play build, no permission, `allow-external-apps`).
- **Phantom processes:** when the shell helper starts, it lifts Android 12+'s child-process limit
  (`max_phantom_processes`, `settings_enable_monitor_phantom_procs`) so builds and servers in Termux survive.
- **Verified on the Xiaomi:** chat session "杭州这一周的天气怎么样": one `bash` call (curl + python), a 7-day
  table in ~6 s. Found on the way: the Google Play Termux build has no `RUN_COMMAND` (replaced with the GitHub
  build 0.118.3); `pkg install curl` alone left curl linked against an old openssl (fixed with `apt full-upgrade`);
  Termux sends `*_original_length` as strings.

### Termux, phase 2 (2026-11-02)
- **Per-session tmux:** every command runs with `TMUX_TMPDIR=~/sessions/<id>/.tmux`, so a session's jobs are its own
  and can be found and ended with it.
- **Clean-up:** Stop and Delete end the session's tmux jobs and running commands (`SessionManager.onEnded` →
  `Termux.cleanUp`; only for sessions that used bash). Files in `~/sessions/<id>` are never deleted, not even
  with the session: coding projects may go on for months (the delete dialog says so).
- **Staying awake:** the app holds a partial wake lock while a command runs; Termux's own wake lock
  (`termux-wake-lock`) is held while any session has tmux jobs, and dropped when none has.
- **Settings → Linux commands (Termux):** shows what is missing with the button that fixes it (download, Google
  Play build, Allow), a Check (bash, curl, tmux, network), and a link to Termux's app settings for autostart/battery.
- **Tests:** 3 pi-host tests (command, session and output across the bridge; Stop kills the command; no bash for
  the supervisor): 22 in all.
- **Verified on the Xiaomi:** Settings check all ✓. Chat task "write a Go HTTP service with a unit test, run it in
  tmux on 8080, keep it running": `pkg install golang`, `go test`, build, tmux, curl, in ~70 s; the server answered
  from the Mac through `adb forward` after the task ended, with Termux's wake lock held; deleting the session
  stopped the server and released the wake lock (that build also removed the folder; since then files are kept). Old session previews now show without Markdown markers.

### UI redesign: bottom tabs (2026-11-02)
- **Three tabs** on a bottom navigation bar: *Sessions* (supervisor, live screens, running and recent
  conversations; a badge counts running ones), *AI providers* (provider, sign-in, sign out, model and thinking
  level), *Settings* (accessibility, live screens, shell backend, Termux, app language, developer, about).
  An open session takes the whole screen; Back returns to the list, which keeps its scroll position.
- **New session** is a button (FAB) that opens a sheet: Chat / Phone task as a segmented choice, with what each does.
- **Session rows:** kind avatar, title, time, preview, status, pause/stop icons; long-press to rename or delete.
- **Chat page:** top bar with back, status and a menu (rename, Pi events, delete); answers as page text, the
  user's messages as bubbles; one message box style everywhere, with a **one-tap clear** button while it has text.
- **Theme:** Material 3 with wallpaper colours on Android 12+, a calm blue otherwise; light and dark follow the system.
  Icons are vector drawables in `res/drawable` (no icon library). Code: `ui/Home.kt`, `Sessions.kt`,
  `Providers.kt`, `SettingsScreen.kt`, `Components.kt`, `Theme.kt`.
- **Verified on the Xiaomi:** all three tabs, the chat page above the keyboard, clear, and the new-session sheet.
- **Markdown on the page:** with answers no longer in bubbles, code blocks, inline code and table headers use the
  surface-container tints (and code blocks an outline); code has its own line height (`CodeStyle`, 18sp) instead
  of the inherited 24sp body line height.

### Messages while a session works: Pi's steering (2026-11-02)
- **Send while working:** a message to a busy session goes to Pi's `Agent.steer`; Pi adds it to the conversation
  after the current turn, so the model reads it before its next step. The bubble shows *Waiting for the current
  step* until Pi takes it (`message_end` echoes the text), and *Not delivered* if the task stops or fails first.
- **Edge cases:** a message for a task Pi hasn't been given yet (waiting for a screen, or being set up) joins the
  task; one that reaches Pi just as its run ends comes back as `steer_late` and is sent as the next message; pi-host
  goes on with anything still queued when the run ends; Stop clears the queue (`clearAllQueues`).
- **Fix:** after Stop during a tool, Pi's last request fails on the aborted signal; that is no longer shown as an error.
- **Tests:** pi-host 25 (+3: steer reaches the model's next request, late steer, Stop drops it); SessionReducer 18 (+6).
- **Verified on the Xiaomi** with the real model: during `sleep 30` in bash, "also run date" was delivered after the
  step and answered in the same run; steering then Stop shows *Not delivered* and no error.

### Verified on a real device (realme RMX2121, Android 12)
- ChatGPT subscription sign-in, then "你好" through Pi (`gpt-6.1-sol`).
- Gmail: sent an email to two different addresses, each after the confirmation card.
- Settings: read the Android version with the app closed; the result arrived as a notification.
- Stop, pause and resume, and renderer-crash recovery.
- Sessions (mock model): a phone task and a chat at once; a second phone task queued and started 59 ms after the
  first ended; pause held every action until resume; a killed process → Interrupted → Continue resumed the
  scripted task at its third step; after a renderer crash the next message carried the whole conversation.
- Sessions (real model): "打开设置，告诉我安卓版本号" and a chat question at the same time; both answered.

## Multi-session work: done (2026-11-01 – 11-02)

All five steps are merged into `main` (`e474abc` … `12b5668`):
- [x] **1. Sessions:** concurrent sessions, saved history, recovery after process death and runtime crashes.
- [x] **2. Built-in shell backend:** pairing, adb over TLS, shell helper with a Binder link.
- [x] **3. A virtual display per phone session:** created, driven and read per display; same-app guard.
- [x] **4. Live previews:** session page and list; adaptive frame rate; nothing sent when hidden; idle displays asleep.
- [x] **5. Supervisor session:** manages the other sessions in words; voice through the phone's speech recognizer.

Open points from this work:
- [ ] **Reboot:** the shell backend is not yet tested across a reboot (Wireless debugging turns off then; on ColorOS
  the user turns it on again, the helper then starts by itself).
- [ ] **Helper memory:** about 89 MB PSS (an app_process outside zygote plus the system context); try a lighter context.
- [ ] **Connect-port scan:** about 10 s the first time on ROMs that announce nothing (then cached); could stop at the
  first adbd-like port sooner or scan likely ranges first.
- [ ] **Same app in two sessions:** impossible (one process, single-task apps move between displays); the supervisor
  is told to wait. A per-app queue could wait automatically instead of failing.
- [ ] **Supervisor history:** it is one long conversation; add "new conversation" for it.
- [ ] **Instrumented tests** for SessionManager, ScreenPool and the helper (today: JVM, pi-host and on-device runs).
- [x] **Slow cold starts:** JD's first two `read_screen`s after `open_app` found no window (JD still starting).
  Now `open_app` waits until the app has a readable window on the display (at most 15 s; otherwise it tells the
  model the app is still starting). Verified on the Xiaomi with the apps force-stopped: ready after 1.4–2.7 s,
  alone and three at once, and every read after `open_app` had content. The 15 s limit itself is untested.
- [ ] **Reclaim gaps (Android 12 fallback only):** nothing is reclaimed on `read_screen`, `screenshot`, `scroll` or
  `swipe`, and the open_app watch stops after ~1.2 s, so a long splash ad can leave a window on the phone's screen;
  root tasks shown with `I=` (no affinity) aren't parsed; tasks are matched by affinity, not package.
- [ ] **Trusted displays:** not yet checked with the phone locked, nor whether a keyboard appears on the session's display.
- [x] **HyperOS freezer:** fixed by closing a session's apps when its task ends (`d1fe7e5`, `54f4610`).
  Was: GreezeManager froze session apps (`frozen uid=… reason=tobg`, again every 5 s, `check binder`)
  once WeiAssist left the foreground; an app that has drawn is skipped ("was show on screen"), one still on its splash
  stays frozen (Taobao: 80 s, `open_app` hit its 15 s limit, a JD tap timed out). Shell can't use `cmd greezer`.
  Background sessions will stall on HyperOS until this is solved (battery "no restrictions" per app? keep them "visible"?).
- [ ] **WeChat's recents entry:** opening WeChat in a session makes WeChat finish its main window on the user's screen
  (one `LauncherUI`); the process and its state stay, the recents entry goes.
- [ ] **Displays only when used:** a phone session gets a display when it starts, even if it only talks (not
  previewed). Could be created at the first phone action instead (changes how sessions queue for screens).
- [ ] **Closing apps on switch:** apps are closed when the task ends, not when `open_app` switches to another app
  mid-task (a multi-app task may go back).

## Next: real-world scenarios on the device

Passed on the Xiaomi: Douban, Bilibili, Xiaohongshu, and shopping on Taobao, JD and Pinduoduo (also in parallel
through the supervisor).

- [ ] **Daily productivity, NetEase Mail (网易邮箱大师, `com.netease.mail`):** open the latest 10 unread emails,
  summarise them, and build a prioritised to-do list for the day.
- [ ] Then Gmail / Outlook, the same task.
- [ ] For each scenario, log failures (wrong taps, missed content, loops, pop-ups) and fix them in tools, prompt or recovery logic.

### Main screen only (no virtual displays)
Tested 2026-11-03 with `--ez mainOnly true` (YouTube: found the channel's subscriber count). Gaps:
- [ ] HyperOS asks "小维助手 wants to open X, allow?" when the app launches another app itself; the model tapped
  allow this time, but it may not always.
- [ ] No preview and no hint in the UI that a session runs on the phone's own screen, or that it waits for it.
- [ ] Mixed mode: a session that fell back to the main screen isn't seen by `guard()`, so a virtual-display session
  can take its app.

## Known gaps and todo

- [ ] **Termux, later:** ask Termux to keep its own wake lock only while jobs run (today it is re-checked after each
  command and clean-up, so a job that ends by itself keeps the lock until the next one); a way to stop a finished
  session's jobs from the UI (now: ask the agent, or delete the session).
- [ ] **Readable confirmation card:** show the target app and a summary (recipient, item and price) instead of raw field lines.
- [x] **Markdown in answers** (2026-11-02): a small built-in renderer, no new dependency. `agent/Markdown.kt` parses
  (headings, lists with nesting, tables with alignment, code blocks, quotes, rules; bold, italic, strike, code,
  links and bare URLs; forgiving with half-streamed text; 8 JVM tests), `app/ui/Markdown.kt` draws it (tables
  as a grid that scrolls sideways, selectable text, clickable links). Session cards, the supervisor card and
  notifications show `Markdown.plain` instead of raw markers. Verified on the Xiaomi (weather table, demo answer).
- [ ] **Ask-the-user tool**, for mid-task questions (preferences, choosing between options) without ending the task.
- [ ] **Direct intents** where they beat UI automation: share, open URL, alarm, calendar.
- [ ] **Per-app permissions** (allow / ask / never) and sensitive-screen detection (FLAG_SECURE, payment, OTP).
- [ ] **Floating assistant bubble** and a wake word (voice input already reaches the supervisor through the phone's speech recognizer).
- [ ] **More providers:** DeepSeek is in; still Anthropic, Google, OpenRouter, local.
- [ ] **Release workflow:** signed builds on tags. Not yet on GitHub: the user still has to pick the org and repo name.
- [ ] **Instrumented tests** for PhoneActions on an emulator.
- [ ] **Repo folder:** the local folder is still `PhoneAgent`; rename it if wanted.

## Working notes

- Set a task on the device (debug build): `adb shell "am start -n com.lanqi.weiassist/.MainActivity --es task '…'"`.
  Add `--ez run true` to start it in a new session, `--ez phone false` for a chat, `--ez mock true` for the mock model,
  `--ez mainOnly true|false` to run phone sessions on the phone's own screen (until the process restarts).
- Debug: `--ez crashPi true` crashes Pi's renderer; `--ez killProcess true` ends the process (session recovery).
- Never `am force-stop` the app on realme: that disables its accessibility service.
- UI dumps of our own Compose screen can be stale; use screenshots to locate buttons.
- Mock provider: `node tools/mock-openai.mjs` with `adb reverse tcp:8787 tcp:8787`, then the dev switch in the app
  (or `--ez mock true`). Scripts for devices: `settings`, `douban`, `browse_settings`, `browse_gmail`, `supervise`,
  `supervise_phone` (a message containing `[[script:NAME]]`).
- Debug: `--es supervise '…'` says it to the supervisor; `--ez shellStart true` starts the shell helper through
  Wireless debugging.
- After installing a new build the helper exits ("app updated"); start it again with `--ez shellStart true`.
- Quote the whole `am start` for `adb shell` when the task has spaces, or the task text is cut at the first space.
- Termux: GitHub build 0.118.3 on the Xiaomi (the Play build has no `RUN_COMMAND`). To type into Termux with
  `adb shell input text`, switch to Gboard first (`ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`;
  Sogou turns letters into Chinese), then back to `com.sohu.inputmethod.sogou.xiaomi/.SogouIME`. Commands for
  the agent run in `~/sessions/<id>`.
- Devices: realme RMX2121 (Android 12, untrusted displays) and Xiaomi `60770a96` (HyperOS, Android 16, trusted).
  Where tasks are: `dumpsys activity activities | grep -E '^Display #|^  \* Task\{'`; moves: `logcat -b events | grep wm_task_moved`.
- The shell helper logs to `/data/local/tmp/weiassist_shell.log`; displays show in `dumpsys display` as `weiassist-<session>`.
- `uiautomator dump` unbinds the app's accessibility service while it runs: an open confirmation card is then
  answered "no". While a card is up, use screenshots and tap by coordinates.
- The logcat buffer is 256 KB and floods on ColorOS: `adb logcat -G 8M`, and filter by pid.
- Measuring: `top -b -n 2 -d 3` (the first block averages since process start); measure release builds (debug
  Compose runs interpreted): `assembleRelease`, zipalign, sign with the debug keystore, install over debug.
  The app is profileable: `simpleperf record --app com.lanqi.weiassist -e cpu-clock -g`; deobfuscate with
  `app/build/outputs/mapping/release/mapping.txt`. UI frame rate: `dumpsys gfxinfo` "Total frames rendered".

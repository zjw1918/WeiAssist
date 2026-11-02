# Architecture

WeiAssist is an Android app that lets an LLM agent operate the phone.
The agent harness is **[Pi](https://github.com/earendil-works/pi)**: its LLM layer (`pi-ai`) and agent loop (`pi-agent-core`).
We embed Pi **as published**: public package exports only, no patches, and upgrades with `npm run upgrade-pi`.
Everything else adapts Android to what Pi expects.

```
┌────────────────────────── :app (Compose UI) ───────────────────────────┐
│ MainActivity · Sessions (list, page) · Onboarding · ModelPicker · AppViewModel │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │ StateFlow<AgentState>, actions
┌────────────────────────── :agent ─┴─────────────────────────────────────┐
│ AgentService   foreground while any session works · Stop all · results   │
│                autoStart() of the shell backend                          │
│ AgentController  owns PiEngine · account and model (AgentReducer)        │
│ SessionManager   every conversation: run · queue · pause · stop · delete │
│ SessionReducer   pure (session, Pi message) → session      ← unit tests │
│ SessionStore     one meta / chat / transcript file per session           │
└──────────────┬───────────────────────────────────────┬──────────────────┘
               │ registerHostCall("phone" / "confirm") │
┌───────── :pi-runtime ──────────────┐   ┌──────────── :phone ────────────┐
│ PiEngine     hidden WebView = JS   │   │ AgentAccessibilityService       │
│ NativeHttp   fetch() for Pi         │   │   read screen · tap · type ·    │
│ LoopbackServer  node:http for OAuth │   │   scroll · keys · confirm card  │
│ SecureStore  Keystore AES-GCM       │   │ PhoneActions (timeouts, pause)  │
│ assets/pi/host.js ← built by Gradle │   └─────────────────────────────────┘
└──────────────┬──────────────────────┘
               │ PiBridge.postMessage ⇄ PiHost.*(json)
┌───────── pi-host/ (TypeScript, bundled by esbuild) ──────────────────────┐
│ host.ts       one Pi Agent per session + Models (login, streamSimple)    │
│ phone-tools.ts  PhoneSession: AgentTools + confirm hook, per session     │
│ native-fetch.ts · shims/ (node:http, node:crypto) · bridge.ts            │
│ node_modules/@earendil-works/pi-ai, pi-agent-core (pinned, unmodified)   │
└──────────────────────────────────────────────────────────────────────────┘
```

## Why Pi runs in a WebView

- Pi's CLI needs Node ≥ 22.19, which isn't available on Android. But `pi-ai` and `pi-agent-core` are browser-safe, and upstream checks that (`check:browser-smoke`).
- A WebView that is never shown gives us a modern V8 with `fetch`, streams and WebCrypto.
- `WebViewAssetLoader` serves the bundle from `https://appassets.androidplatform.net`.
- `addWebMessageListener` accepts messages from that origin only.

## The bridge protocol

All messages are JSON.

**JS → Kotlin:** `PiBridge.postMessage(json)`. Kotlin → JS: `evaluateJavascript("PiHost.<fn>(json)")`.

| Direction | Message | Purpose |
|---|---|---|
| JS→K | `ready`, `status`, sign-in events | App-wide state ([AgentReducer](../agent/src/main/java/com/lanqi/weiassist/agent/AgentReducer.kt)) |
| JS→K | `configured`, agent events (`text_delta`, `tool_start`, …), `idle`, `error`, all with `session` | A session's state ([SessionReducer](../agent/src/main/java/com/lanqi/weiassist/agent/SessionReducer.kt)) |
| JS→K | `transcript` (`session`, `messages`) | After the user's message and every turn: Pi's transcript to save, without screenshots and old screens |
| JS→K | `credentials_changed` | Pi's credential store was written. Kotlin persists it encrypted. |
| JS→K | `fetch` / `fetch_abort` | Native HTTP for Pi (no CORS). The reply streams back as `fetchHead`, `fetchChunk`, `fetchEnd` or `fetchError`. |
| JS→K | `http_listen` / `http_close` / `http_response` | The `node:http` shim. Pi's OAuth callback server runs on a real loopback socket. |
| JS→K | any registered host call (`gate`, `phone`, `confirm`), with `session` | `bridgeCall(type, …)`, answered with `PiHost.reply(id, ok, value)`. Tool arguments are nested under `args`. `gate` runs before every tool call and holds a paused session. |
| K→JS | `init`, `status`, `login`, `answer`, `cancelLogin`, `logout` | App-wide commands |
| K→JS | `configure` (optionally with saved `messages`), `prompt`, `abort`, `dispose`, all with `session` | Session commands |

Tests: [pi-host/test](../pi-host/test) runs the shipped bundle against a fake Kotlin side that speaks this exact protocol.

## The shell backend (`:shell`)

Virtual displays for other apps, input on any display and `am start --display` need the shell user's
permissions. WeiAssist gets them without a computer, Shizuku or root:

```
 WeiAssist (app uid)                                   adbd (Wireless debugging)
 ── PairingService ── TLS 1.3 + SPAKE2 ──────────────▶ pairing port   (once: our key is trusted)
 ── ShellBackend ──── TLS 1.3 (our key), shell v2 ───▶ connect port   (each start)
                         │ CLASSPATH=base.apk setsid app_process … ShellServer
                         ▼
 ShellBinderProvider ◀── Binder ──────────────────── ShellServer (uid 2000, outlives the app's process)
```

- **Pairing** is `adb pair`, byte for byte (AOSP `pairing_auth.cpp`, `pairing_connection.cpp`): BoringSSL's
  SPAKE2 through a small JNI library (`libadbpair`), TLS 1.3 and keying-material export through a bundled
  Conscrypt, HKDF and AES-128-GCM in Kotlin. The user types the code into a notification (inline reply),
  because Settings' pairing dialog closes when they switch apps. The key is RSA-2048, stored encrypted
  with a Keystore key (`AdbKeyStore`).
- **Connecting** is `adb connect`: CNXN → STLS → TLS with our certificate → CNXN, then `shell,v2,raw:`.
  The connect port changes every time Wireless debugging is turned on: the last one that worked, then
  `service.adb.tls.port`, then mDNS, then a scan of the phone's own loopback for a port that answers like
  adbd (some ROMs announce nothing).
- **The helper** (`ShellServer`) is our own APK's code run by `app_process` as shell. It hands its Binder
  to the app through `ShellBinderProvider` (accepts only uid 2000/0); it serves only the app's uid. When
  the app's process dies it waits (watching `/proc`) and hands itself over again; it never starts the app.
  It exits when its APK is replaced or removed; the app starts a new one (`autoStart`).
- **After a reboot** Wireless debugging is off, so the helper can't start until the user turns it on
  (the app then starts it by itself). Where the phone lets the helper grant `WRITE_SECURE_SETTINGS`, the
  app could switch Wireless debugging on itself; ColorOS refuses that grant.

## Sessions

Every conversation is a session: its own Pi `Agent` (transcript, tools, phone state) inside the one Pi
runtime. One WebView for all sessions keeps memory flat; Pi's agent loop is asynchronous, so sessions
run side by side. A session runs until its task ends or the user stops it; leaving its page changes nothing.

- **Memory:** every session's summary (title, status, last line) is kept. Its chat is in memory only while
  it is busy or open on screen (`SessionManager.attach`/`detach`); its transcript lives inside Pi while its
  Agent exists. Chat and trace are capped per session.
- **Disk:** `files/sessions/<id>.{meta,chat,pi}.json`, written atomically and coalesced (a burst of steps
  is one write). The list reads only the `meta` files.
- **Restoring:** a session gets an Agent on its first message in a runtime, with its saved transcript.
  So a session continues after the app restarts or Pi's renderer dies. Sessions that were working when
  the process died show as *Interrupted*; Continue sends a "continue where you left off" message.
- **The screen:** phone sessions need a screen (`ScreenPool`). Today there is one, the phone's own, so
  phone sessions take turns (*Waiting for the screen*) while chat sessions always run. Only the holder may act.
- **Pause:** Pi's `beforeToolCall` asks Kotlin's `gate` before every tool call; a paused session waits
  there, and a phone action already waiting gives way. Stop aborts the session's Agent.

## Why each piece exists

- **Native fetch:** browsers enforce CORS, and providers' error responses (401, 429) carry no CORS headers. In a WebView every API error would surface as "Connection error". Node, Pi's home runtime, has no CORS either.
- **Loopback server:** Pi's "Sign in with ChatGPT" (`openaiChatGPTOAuth`) listens on `127.0.0.1:1455` for the browser redirect. The `node:http` shim forwards that socket to Pi's own handler, which checks `state`.
- **Credential store:** Pi's `CredentialStore` interface, mirrored to Kotlin after every write and encrypted with an Android Keystore key. OAuth refresh is Pi's.
- **Foreground service:** a task must survive the user leaving or closing the app. While Pi works, `AgentService` is a started foreground service of type `specialUse`.
- **Renderer recovery:** if the WebView renderer dies (OOM, killed), `PiEngine` boots a fresh Pi. Credentials survive, and a running task ends with a clear message.

## Phones that freeze background apps

Some vendors' battery managers (realme/ColorOS `OplusHansManager`, and similar on other OEMs) freeze
background apps within about 30 s. They ignore foreground services and the battery-optimization exemption.

- **During a task** the bound accessibility service keeps the app unfrozen (`importance=accessibility`).
- **During ChatGPT sign-in** without accessibility on, the app may freeze while Chrome is in front. The
  browser's redirect then waits in the loopback socket's queue and is answered as soon as the user
  returns to the app (✕ in Chrome, or the notification). The sign-in screen tells the user to do that.

## Safety model

1. **Irreversible actions:** a tap on Send/Pay/Delete-like labels goes through Pi's `beforeToolCall` hook, which shows an overlay card. Declining blocks the tap and ends the run.
2. **Our own screen:** the agent never operates WeiAssist itself. While the user looks at it, phone actions pause (`open_app` excepted).
3. **Password fields:** never sent to the model.
4. **Kill switch:** Stop on a session (or Stop all in the notification) aborts Pi's run and releases paused actions.
5. **Confirmation names the session:** the card says which conversation wants to tap.

The threat model (prompt injection from screen content, sensitive apps) is tracked in [ROADMAP.md](ROADMAP.md).

## Building

- **App:** `./gradlew assembleDebug`. `:pi-runtime` runs `buildPiHost<Variant>` (npm ci, tsc, esbuild) into generated assets, so you need Node from `.nvmrc`.
- **Tests:** `cd pi-host && npm test` for the bundle end to end; `./gradlew testDebugUnitTest` for the JVM tests.
- **Mock provider:** `node tools/mock-openai.mjs` with `adb reverse tcp:8787 tcp:8787`, then the dev switch in the app.

# Architecture

WeiAssist is an Android app that lets an LLM agent operate the phone.
The agent harness is **[Pi](https://github.com/earendil-works/pi)**: its LLM layer (`pi-ai`) and agent loop (`pi-agent-core`).
We embed Pi **as published**: public package exports only, no patches, and upgrades with `npm run upgrade-pi`.
Everything else adapts Android to what Pi expects.

```
┌────────────────────────── :app (Compose UI) ───────────────────────────┐
│ MainActivity · Onboarding · ModelPicker · AppViewModel (binds the service)│
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │ StateFlow<AgentState>, actions
┌────────────────────────── :agent ─┴─────────────────────────────────────┐
│ AgentService   foreground while working · Stop · result notification    │
│ AgentController  owns PiEngine · applies AgentReducer · effects          │
│ AgentReducer   pure (state, Pi message) → (state, effect)   ← unit tests │
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
│ host.ts       Pi's Agent + Models (createModels, login, streamSimple)    │
│ phone-tools.ts  AgentTools + beforeToolCall confirm hook                 │
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
| JS→K | `ready`, `status`, `configured`, agent events (`text_delta`, `tool_start`, …), `idle`, `error` | State for the UI ([AgentReducer](../agent/src/main/java/com/lanqi/weiassist/agent/AgentReducer.kt)) |
| JS→K | `credentials_changed` | Pi's credential store was written. Kotlin persists it encrypted. |
| JS→K | `fetch` / `fetch_abort` | Native HTTP for Pi (no CORS). The reply streams back as `fetchHead`, `fetchChunk`, `fetchEnd` or `fetchError`. |
| JS→K | `http_listen` / `http_close` / `http_response` | The `node:http` shim. Pi's OAuth callback server runs on a real loopback socket. |
| JS→K | any registered host call (`phone`, `confirm`) | `bridgeCall(type, …)`, answered with `PiHost.reply(id, ok, value)`. Arguments are nested under `args`. |
| K→JS | `init`, `status`, `login`, `answer`, `cancelLogin`, `logout`, `configure`, `prompt`, `abort` | Commands |

Tests: [pi-host/test](../pi-host/test) runs the shipped bundle against a fake Kotlin side that speaks this exact protocol.

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
4. **Kill switch:** Stop in the notification aborts Pi's run and releases paused actions.

The threat model (prompt injection from screen content, sensitive apps) is tracked in [ROADMAP.md](ROADMAP.md).

## Building

- **App:** `./gradlew assembleDebug`. `:pi-runtime` runs `buildPiHost<Variant>` (npm ci, tsc, esbuild) into generated assets, so you need Node from `.nvmrc`.
- **Tests:** `cd pi-host && npm test` for the bundle end to end; `./gradlew testDebugUnitTest` for the JVM tests.
- **Mock provider:** `node tools/mock-openai.mjs` with `adb reverse tcp:8787 tcp:8787`, then the dev switch in the app.

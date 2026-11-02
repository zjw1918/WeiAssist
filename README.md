# PhoneAgent · 小维助手 (spike: Pi on Android)

Milestone 0: send "你好" through **Pi** (github.com/earendil-works/pi, v1.4.0) to OpenAI and show the reply.

## How Pi runs on Android
Pi is TypeScript. The CLI (`pi-coding-agent`) needs Node ≥ 22.19, which Android doesn't have
(nodejs-mobile stops at Node 18). But Pi's two core layers are browser-safe and checked so upstream (`check:browser-smoke`):
- `@earendil-works/pi-ai`: providers, auth, streaming (OpenAI, Anthropic, Google, DeepSeek, …)
- `@earendil-works/pi-agent-core`: the `Agent` loop, tool calls, events

So `pi-host/` bundles those two packages (pinned 1.4.0) plus a ~90-line adapter (`src/host.ts`) with esbuild
into `app/src/main/assets/pi/host.js`. `PiEngine.kt` loads it in a WebView that is never shown, served from
`https://appassets.androidplatform.net` by `WebViewAssetLoader`. That WebView is used only to run JavaScript.
- Kotlin → Pi: `evaluateJavascript("PiHost.configure|prompt|abort(json)")`
- Pi → Kotlin: `PiBridge.postMessage(json)` through `WebViewCompat.addWebMessageListener`, accepted only from the app's own origin

Every model request is built by Pi's `openai-responses` adapter (the official `openai` JS SDK inside pi-ai);
Kotlin only carries the bytes (see Networking).

## Build
    cd pi-host && npm install --ignore-scripts && npm run build   # → app/src/main/assets/pi/
    ./gradlew installDebug

## Signing in (both methods are Pi's own)
The welcome screen lists the auth methods Pi's `openai` provider declares, and `models.login()` runs them:
- **ChatGPT subscription**: Pi's `openaiChatGPTOAuth` ("Sign in with ChatGPT"). This is PKCE with dynamic client
  registration; the app introduces itself as 小维助手 and the token is used directly against api.openai.com.
  - Chrome opens as a Custom Tab, and the redirect goes to `http://127.0.0.1:1455/auth/callback`.
  - That port is a loopback socket in Kotlin (`LoopbackServer`) behind a `node:http` shim, and Pi's own handler checks state and answers.
  - Pi's "paste the redirect URL" fallback is shown as "Chrome 没跳回来？".
  - The flow is embedded with `registerBundledOAuthFlowLoaders`, as Pi's standalone binary does.
- **API key**: Pi's `envApiKeyAuth` asks for a secret. API usage is billed separately from ChatGPT and needs
  billing set up at platform.openai.com.
- Pi's credential store is mirrored to Kotlin after every write (login, token refresh, logout) and kept
  AES-GCM encrypted with an Android Keystore key (`SecureStore`). Pi refreshes ChatGPT tokens itself.

## Networking: native fetch
`globalThis.fetch` inside the WebView is replaced by `native-fetch.ts`, which hands each request to Kotlin
(`NativeHttp`, HttpURLConnection) and streams the body back. This is needed because browsers enforce CORS,
and OpenAI's error responses (401, 429…) carry no CORS headers, so the WebView reported every error as
"Connection error". Node, Pi's home runtime, has no CORS either.

## Test without spending tokens
    node tools/mock-openai.mjs 8787      # logs every request Pi sends
    adb reverse tcp:8787 tcp:8787
then sign in (any API key works) and turn on "开发用：发到本地模拟服务器" (debug builds).

## Models and thinking levels
Both pickers are filled from Pi at runtime:
- **Models:** `models.getModels("openai")`. With a ChatGPT sign-in, this is narrowed to Pi's `OPENAI_CODEX_MODELS` catalog, the models OpenAI offers to ChatGPT accounts. Others fail with `model_not_found … not supported when using Codex with a ChatGPT account`.
- **Default model:** Pi CLI's default for the provider (`gpt-6.1-sol`).
- **Thinking levels:** `getSupportedThinkingLevels(model)`. The chosen level goes into the Agent's `thinkingLevel` through `clampThinkingLevel`.

## Phones that freeze background apps (realme/ColorOS and others)
`OplusHansManager` freezes the app about 30 s after Chrome takes the screen, even with a foreground service, a visible notification or the battery-optimization exemption. Chrome's callback then waits in the socket queue, and is answered when the app comes back. The sign-in screen and notification therefore tell the user: after Allow, tap ✕ (or the notification). The loopback server ignores clients that have hung up, and credentials are saved synchronously, before anything else can kill the process.

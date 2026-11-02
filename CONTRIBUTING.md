# Contributing to WeiAssist

Thanks for helping build an assistant that really works your phone for you! This guide gets you from clone to pull request.

## Ground rules

- **Use Pi as published.** Import only public exports of `@earendil-works/pi-*`, never deep `dist/` paths, and never patch Pi. If Pi lacks something, adapt the environment (a shim, a host call, an `AgentTool`) or propose it upstream.
- **Safety first.** Anything that lets the agent act needs a story for confirmation, privacy and Stop. Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#safety-model).
- **Both languages.** User-facing text lives in string resources with English (`values/`) and Chinese (`values-zh/`). If you can't write one of them, say so in the PR and we'll help.
- **Small, tested changes.** New logic comes with tests (see below).

## Setup

- JDK: CI uses Temurin 25; Gradle provisions its daemon JVM automatically.
- Node per [`.nvmrc`](.nvmrc), with npm on PATH. Gradle builds the Pi bundle.
- Android SDK with platform 37. Android Studio works out of the box.

```bash
./gradlew assembleDebug          # builds pi-host too
./gradlew installDebug           # on a device or emulator
```

On the device, turn on **WeiAssist** under Settings → Accessibility to use phone tools.

## Tests

| What | Command | Where |
|---|---|---|
| Pi bundle, end to end (login, streaming, tools, confirmation) | `cd pi-host && npm test` | `pi-host/test/` |
| Agent state transitions | `./gradlew testDebugUnitTest` | `agent/src/test/` |
| Lint | `./gradlew lint` | |

The mock provider ([tools/mock-openai.mjs](tools/mock-openai.mjs)) records requests and can play scripted tool calls (`[[script:NAME]]` in the prompt). Use it instead of a real API in tests.

## Making changes

- **A new phone tool:**
  1. Add an `AgentTool` in [pi-host/src/phone-tools.ts](pi-host/src/phone-tools.ts) (TypeBox schema, `executionMode: "sequential"`, honour `signal`).
  2. Handle its action in [PhoneActions](phone/src/main/java/com/lanqi/weiassist/phone/PhoneActions.kt).
  3. Add a scripted-tool test.
- **A new provider:** Pi already has it. Register Pi's provider in `host.ts`, and the UI picks up its auth methods and models.
- **Upgrading Pi:** `cd pi-host && npm run upgrade-pi`, then run all tests. Type errors mean Pi's API changed; adapt our code, not Pi.

## Pull requests

- One topic per PR, with a clear description of what and why. Add screenshots for UI changes.
- CI must pass: Pi host tests, lint, unit tests and the debug build.
- Commit messages: imperative and specific (`fix(phone): …`, `feat(agent): …`).
- By contributing you agree your work is licensed under the [Apache License 2.0](LICENSE).

## Code of conduct

Be kind and constructive. See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Report security issues privately ([SECURITY.md](SECURITY.md)).

# Security policy

WeiAssist operates other apps on your phone and holds your AI provider credentials. We treat security
reports as the highest priority.

## Reporting a vulnerability

Please **do not open a public issue**. Report privately through GitHub: this repository's **Security**
tab → **Report a vulnerability**. Include what you found, how to reproduce it, and the impact you see.
We aim to acknowledge reports within 3 days and to ship a fix or mitigation as fast as the severity requires.

## What counts

Especially welcome:

- **Prompt injection that leads to action:** screen content or a message that makes the agent act without the user's intent, or skip the confirmation step.
- **Getting around the confirmation gate** for sending, paying or deleting.
- **Leaks of credentials** (API keys, ChatGPT tokens) or of private screen content: to logs, other apps, or the model provider beyond what a task needs.
- **Abusing the app's exported surfaces:** deep links, the loopback sign-in server (127.0.0.1), the WebView bridge.

## How the app protects you today

- **Credentials:** stored only on the device, AES-GCM encrypted with a key that never leaves the Android Keystore. They are never logged.
- **Password fields:** never sent to the model; it only learns that a password field exists.
- **Irreversible actions:** taps on Send/Pay/Delete-like buttons need your explicit OK on an overlay card.
- **Its own screen:** the agent never operates WeiAssist itself, and it pauses while you are looking at it.
- **Stop button:** a running task can always be stopped from its notification.
- **WebView bridge:** messages are accepted only from the app's own asset origin. The sign-in callback server listens on loopback only.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and the safety section of [docs/ROADMAP.md](docs/ROADMAP.md).

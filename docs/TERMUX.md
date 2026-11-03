# Linux commands with Termux

WeiAssist can run Linux commands on your phone through [Termux](https://termux.dev). This lets the
assistant look things up on the web (weather, news), work with data, and write and test code. This is
optional: everything else works without it.

[中文说明见下文](#中文termux-设置)

## How it works

The assistant has a `bash` tool. Each command runs in Termux, a separate app, through Termux's
`RUN_COMMAND` service. Commands run as Termux's user, not as WeiAssist, so they can't read WeiAssist's
files or your sign-in.

- Each conversation works in its own folder, `~/sessions/<id>` in Termux's home.
- Packages you or the assistant install with `pkg` are shared by all conversations.
- A command is stopped after 2 minutes by default (10 at most). Longer jobs, such as servers and builds,
  run in `tmux` and keep going after the answer.
- Pressing **Stop**, or deleting a conversation, ends its running commands and `tmux` jobs.
- **Files are never deleted**, not even with the conversation, so a project can go on for weeks. Delete
  them yourself in Termux if you no longer need them.

## Setup

### 1. Install Termux from GitHub or F-Droid

Download the latest `termux-app_*_universal.apk` (or the one for your CPU, usually `arm64-v8a`) from
**[Termux's GitHub releases](https://github.com/termux/termux-app/releases/latest)**, or install Termux
from [F-Droid](https://f-droid.org/packages/com.termux/).

> **Don't use the Google Play build.** It can't run commands for other apps. If you have it, uninstall
> it first (its files are lost), then install the GitHub or F-Droid build. Builds from different sources
> can't be installed over each other.

### 2. Prepare Termux

Open Termux, wait for it to finish setting up, then run:

```bash
pkg update && pkg upgrade -y
pkg install -y curl tmux
mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties
termux-reload-settings
```

- `pkg upgrade` matters: on a fresh install, `curl` may otherwise fail with an OpenSSL error.
- `allow-external-apps=true` lets other apps (here WeiAssist) send commands to Termux.

### 3. Allow WeiAssist to run commands in Termux

In WeiAssist, open **Settings → Linux commands (Termux)** and tap **Allow**.

If Android doesn't ask (for example after you declined once), allow it by hand: Android **Settings →
Apps → WeiAssist → Permissions → Additional permissions → Run commands in Termux environment → Allow**.
The menu names differ a little between phone makers.

### 4. Let Termux run in the background

Many phones stop background apps to save battery, which ends long commands. In WeiAssist, tap
**Termux app settings**, and there:

- set battery use to **Unrestricted** (or "No restrictions");
- allow **Autostart** (Xiaomi, OPPO, realme, vivo, Huawei and others have this switch).

**Android 12 and later** also stops apps that start many processes (the "phantom process" limit),
which can kill builds and test runs. If you use WeiAssist's shell helper (Settings → Shell backend),
it lifts this limit when it starts. Otherwise you can lift it once from a computer with `adb`:

```bash
adb shell device_config put activity_manager max_phantom_processes 2147483647
adb shell settings put global settings_enable_monitor_phantom_procs false
```

### 5. Check

In **Settings → Linux commands (Termux)**, tap **Check**. It shows whether `bash`, `curl`, `tmux` and the
network work. When everything has a ✓, try it in a chat:

> What's the weather in Hangzhou this week?

## Troubleshooting

| What you see | What to do |
| --- | --- |
| "Termux is not installed" | Install Termux from GitHub or F-Droid (step 1). |
| "Google Play build" | Uninstall Termux, then install the GitHub or F-Droid build (step 1). |
| "Allow this app to run commands in it" | Grant the permission (step 3). |
| "Termux could not run the command" | Usually `allow-external-apps` is missing: repeat step 2, then try again. |
| "No answer from Termux" | Open Termux once, and allow it to run in the background (step 4). |
| `curl ✗` or `tmux ✗` in Check | Run `pkg install -y curl tmux` in Termux. |
| `curl` fails with an SSL or library error | Run `pkg upgrade -y` (or `apt full-upgrade -y`) in Termux. |
| Long jobs stop when the screen is off | Step 4: battery Unrestricted, Autostart, and the phantom process limit. |

## Privacy and safety

- The assistant can run any command in Termux: read and change files in Termux's home, install packages
  and use the network. It can't reach WeiAssist's data, other apps' data, or anything that needs root.
- What a command prints is sent to the AI provider you signed in with, like the rest of the conversation.
  Don't keep secrets in Termux that you wouldn't show the assistant.
- The assistant treats text from the web as information, not as instructions.

---

## 中文：Termux 设置

小维助手可以通过 [Termux](https://termux.dev) 在手机上运行 Linux 命令，用来上网查信息（天气、新闻）、
处理数据、编写和测试代码。这是可选功能，不装 Termux 其他功能照常使用。

### 工作方式

- 助手的 `bash` 工具通过 Termux 的 `RUN_COMMAND` 服务执行命令。命令以 Termux 的身份运行，读不到小维助手的文件和登录凭据。
- 每个对话有自己的文件夹 `~/sessions/<id>`；用 `pkg` 安装的软件包所有对话共用。
- 单条命令默认 2 分钟后停止（最长 10 分钟）。服务器、编译等长任务在 `tmux` 里运行，回答结束后继续。
- 点 **停止** 或删除对话，会结束该对话正在运行的命令和 `tmux` 任务。
- **文件永不删除**，删除对话也保留，需要时请在 Termux 里自己删除。

### 设置步骤

1. **从 GitHub 或 F-Droid 安装 Termux**：在 [Termux 的 GitHub 发布页](https://github.com/termux/termux-app/releases/latest)
   下载 `termux-app_*_universal.apk`（或 `arm64-v8a` 版），或从 [F-Droid](https://f-droid.org/packages/com.termux/) 安装。
   **不要用 Google Play 版**，它不能替其他应用运行命令；如已安装，请先卸载（其中的文件会丢失）。
2. **准备 Termux**：打开 Termux，等它初始化完成后运行：

   ```bash
   pkg update && pkg upgrade -y
   pkg install -y curl tmux
   mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties
   termux-reload-settings
   ```

   `pkg upgrade` 不能省，否则新装的 `curl` 可能报 OpenSSL 错误。
3. **授权小维助手**：在小维助手 **设置 → Linux 命令（Termux）** 点 **允许**。如果系统没有弹窗，请到系统
   **设置 → 应用 → 小维助手 → 权限 → 其他权限 → 在 Termux 环境中运行命令 → 允许**（各品牌菜单名称略有不同）。
4. **允许 Termux 后台运行**：在小维助手点 **Termux 应用设置**，把省电策略设为 **无限制**，并允许 **自启动**。
   Android 12 及以上还会限制应用的子进程数量（"phantom process"），可能中断编译和测试。小维助手的 Shell
   后台启动时会自动解除这个限制；没有使用 Shell 后台的话，可以在电脑上用 `adb` 运行上文英文部分的两条命令。
5. **检查**：在 **设置 → Linux 命令（Termux）** 点 **检查**，`bash`、`curl`、`tmux` 和网络都显示 ✓ 即可。
   然后在对话里试试：“杭州这一周的天气怎么样？”

### 常见问题

| 提示 | 处理方法 |
| --- | --- |
| 未安装 Termux | 按第 1 步安装 GitHub 或 F-Droid 版。 |
| 这是 Google Play 版 | 卸载后安装 GitHub 或 F-Droid 版。 |
| 请允许本应用在其中运行命令 | 按第 3 步授权。 |
| Termux 无法运行命令 | 多半缺少 `allow-external-apps`，重做第 2 步。 |
| Termux 没有响应 | 打开一次 Termux，并按第 4 步允许后台运行。 |
| 检查里 `curl ✗` 或 `tmux ✗` | 在 Termux 里运行 `pkg install -y curl tmux`。 |
| `curl` 报 SSL 或库错误 | 在 Termux 里运行 `pkg upgrade -y`（或 `apt full-upgrade -y`）。 |
| 熄屏后长任务被停止 | 按第 4 步设置省电、自启动和子进程限制。 |

### 隐私与安全

助手可以在 Termux 里运行任意命令：读写 Termux 主目录的文件、安装软件包、访问网络，但读不到小维助手和其他应用的数据，也没有 root 权限。
命令的输出会和对话其他内容一样发给你登录的 AI 服务商，请不要在 Termux 里存放不想让助手看到的机密信息。

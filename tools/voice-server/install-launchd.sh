#!/bin/zsh
# Runs the voice server at login and restarts it if it stops. `--uninstall` removes it.
set -eu

label=com.lanqi.weiassist.voice
plist="$HOME/Library/LaunchAgents/$label.plist"
domain="gui/$(id -u)"

launchctl bootout "$domain/$label" 2>/dev/null || true
if [[ "${1:-}" == "--uninstall" ]]; then
  rm -f "$plist"
  echo "Removed $label."
  exit 0
fi

uv_bin="$(command -v uv)"
ffmpeg_bin="$(command -v ffmpeg)"
server="${0:A:h}/server.py"
log="$HOME/Library/Logs/weiassist-voice.log"

mkdir -p "${plist:h}"
cat >"$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$label</string>
  <key>ProgramArguments</key>
  <array>
    <string>$uv_bin</string><string>run</string><string>--script</string><string>$server</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>${ffmpeg_bin:h}:/usr/bin:/bin</string>
    <key>HF_HUB_OFFLINE</key><string>1</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>15</integer>
  <key>StandardOutPath</key><string>$log</string>
  <key>StandardErrorPath</key><string>$log</string>
</dict>
</plist>
EOF
plutil -lint "$plist" >/dev/null
launchctl bootstrap "$domain" "$plist"
echo "Installed $label. Log: $log"

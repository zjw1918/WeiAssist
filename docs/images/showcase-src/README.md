# README showcase

`showcase.html` puts four real screenshots (a realme RMX2121, 2026-11-05; status bar blanked, 540×1200) in phone
frames with English and Chinese captions. Render it with headless Chrome:

```bash
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars \
  --window-size=1920,940 --screenshot=../showcase.png "file://$PWD/showcase.html"
```

1. A DeepSeek Harness session with three subagents at work (their steps marked ↳).
2. The same session's answer: one comparison table from the three briefs.
3. Settings → AI engine: Pi or DeepSeek Harness.
4. A Pi session: the weather via curl in Termux, as a table.

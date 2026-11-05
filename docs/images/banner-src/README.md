# README banners

`banner.html` draws the README banners; render them with headless Chrome at 2× (1280×400 → 2560×800):

```bash
C="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
for t in dark light; do
  "$C" --headless=new --hide-scrollbars --force-device-scale-factor=2 --window-size=1280,400 \
    --screenshot=../banner-$t.png "file://$PWD/banner.html?theme=$t"
  "$C" --headless=new --hide-scrollbars --force-device-scale-factor=2 --window-size=1280,400 \
    --screenshot=../banner-zh-$t.png "file://$PWD/banner.html?theme=$t&lang=zh"
done
```

- `weiassist-icon.svg`: the launcher icon (`app/src/main/res/drawable/ic_launcher_*.xml`) as SVG.
- `pi.svg`: Pi's logo (https://pi.dev/logo-auto.svg, cropped).
- `dsh-favicon*.svg`: DeepSeek Harness's icon, light and dark (deepseek-ai/deepseek-harness, `apps/web/public/`).
- `termux-icon.png`: Termux's icon (termux/termux-app, `fastlane/metadata/android/en-US/images/icon.png`).

The logos are their owners' trademarks, used to name the engines and tools WeiAssist works with.

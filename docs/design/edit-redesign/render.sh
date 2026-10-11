#!/bin/bash
# 用法:render.sh a1 b1 ...  → docs/screenshots/edit-redesign/<name>.png(1920×1080)
cd "$(dirname "$0")"
OUT=../../screenshots/edit-redesign; mkdir -p $OUT
for n in "$@"; do
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --disable-gpu --hide-scrollbars \
    --force-device-scale-factor=2 --window-size=960,540 --virtual-time-budget=4000 \
    --screenshot="$PWD/$OUT/$n.png" && sips -s format jpeg -s formatOptions 88 "$PWD/$OUT/$n.png" --out "$PWD/$OUT/$n.jpg" >/dev/null && rm "$PWD/$OUT/$n.png" "file://$PWD/$n.html" 2>/dev/null
done
ls -la $OUT

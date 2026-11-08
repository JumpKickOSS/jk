#!/usr/bin/env bash
# Records the agent demo: Claude Code fixing the bug in the terminal (agent.tape, left) beside the
# jk web dashboard following the same runs (record-dashboard.cjs, right), then lines the two
# videos up by their start stamps and writes hosting/public/agent-demo.{webm,mp4} and its poster.
#
#   docs/assets/demo/setup.sh --agents && docs/assets/demo/record-agent.sh
#
# Needs vhs, ffmpeg, Google Chrome, and playwright-core where NODE_PATH finds it (default:
# ~/src/scratch/demo-recorder/node_modules, made by `npm i playwright-core` in that directory).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
demo="${JK_DEMO_DIR:-$HOME/src/scratch/demo-petclinic}"
export JK_STATE_DIR="${JK_DEMO_STATE:-$HOME/src/scratch/demo-state}"
export JK_HTTP_PORT="${JK_DEMO_PORT:-8921}"
export NODE_PATH="${NODE_PATH:-$HOME/src/scratch/demo-recorder/node_modules}"
export DEMO_OUT="$root/target/demo"
rm -rf "$DEMO_OUT"
mkdir -p "$DEMO_OUT"

url="$(cd "$demo" && jk web -n 2>&1 | grep -o 'http://127.0.0.1:[0-9]*/#t=[A-Za-z0-9_=-]*' | head -1)"
project="$(find "$JK_STATE_DIR/builds/projects" -mindepth 1 -maxdepth 1 -type d -printf "%f\n" | head -1)"
[[ -n "$url" && -n "$project" ]] || { echo "no dashboard URL or project: run setup.sh --agents first" >&2; exit 1; }

node "$here/record-dashboard.cjs" "$url" "$project" "$DEMO_OUT" "$DEMO_OUT/stop" &
recorder=$!
while [[ ! -f "$DEMO_OUT/dashboard-start" ]]; do sleep 0.2; done

(cd "$root" && vhs "$here/agent.tape")
touch "$DEMO_OUT/stop"
wait "$recorder"

# The take ends where the terminal goes still for good: cut three seconds after its last change.
still="$(ffmpeg -hide_banner -nostats -i "$DEMO_OUT/agent-terminal.mp4" -vf freezedetect=n=0.001:d=12 -an -f null - 2>&1 \
  | grep -o 'freeze_start: [0-9.]*' | tail -1 | grep -o '[0-9.]*$' || true)"
duration="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$DEMO_OUT/agent-terminal.mp4")"
end="$(python3 -c "s='$still'; print(min(float('$duration'), float(s) + 3) if s else float('$duration'))")"
echo "take ends at: ${end}s of ${duration}s"

offset="$(python3 -c "print(max(0.0, float(open('$DEMO_OUT/terminal-start').read()) - float(open('$DEMO_OUT/dashboard-start').read())))")"
echo "dashboard offset: ${offset}s"
ffmpeg -loglevel error -y -i "$DEMO_OUT/agent-terminal.mp4" -ss "$offset" -i "$DEMO_OUT/dashboard.webm" \
  -filter_complex "[1:v]fps=30,setpts=PTS-STARTPTS[d];[0:v]setpts=PTS-STARTPTS[t];[t][d]hstack=inputs=2:shortest=1[v]" \
  -map "[v]" -t "$end" -c:v libx264 -preset slow -crf 24 -pix_fmt yuv420p "$DEMO_OUT/agent-demo-full.mp4"
echo "composited: $DEMO_OUT/agent-demo-full.mp4"

# The page's copies: VP9 and H.264 at 1920×1080, and the last frame as the poster.
pub="$root/hosting/public"
ffmpeg -loglevel error -y -i "$DEMO_OUT/agent-demo-full.mp4" -c:v libvpx-vp9 -b:v 0 -crf 38 -row-mt 1 -an "$pub/agent-demo.webm"
ffmpeg -loglevel error -y -i "$DEMO_OUT/agent-demo-full.mp4" -c:v libx264 -preset slow -crf 26 -pix_fmt yuv420p -movflags +faststart -an "$pub/agent-demo.mp4"
ffmpeg -loglevel error -y -sseof -0.5 -i "$DEMO_OUT/agent-demo-full.mp4" -frames:v 1 -c:v libwebp -quality 82 "$pub/agent-demo-poster.webp"
ls -la "$pub"/agent-demo*

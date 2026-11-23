#!/bin/bash
# 월드·설정을 save/ 로 복사하고, 바뀐 게 있으면 이 브랜치에 커밋·푸시한다.
# 서버가 켜져 있으면 save-off → save-all flush → 복사 → save-on 순서로 일관된 상태를 뜬다.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
PAPER_DIR="${PAPER_DIR:-/home/user/paper-server}"
REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
BRANCH=ccr-7b5bb142-86ezy7
SAVE="$HERE/save"

exec 9>"$PAPER_DIR/.backup.lock"
flock -n 9 || { echo "백업: 이미 진행 중"; exit 0; }

if [ "$(git -C "$REPO" rev-parse --abbrev-ref HEAD)" != "$BRANCH" ]; then
  echo "백업 중단: 현재 브랜치가 $BRANCH 가 아님"; exit 1
fi

running=false
tmux has-session -t paper 2>/dev/null && running=true
flushed=""
if $running; then
  n=$(wc -l < "$PAPER_DIR/console.log")
  tmux send-keys -t paper "save-off" Enter
  tmux send-keys -t paper "save-all flush" Enter
  flushed="(저장 대기 시간 초과)"
  for _ in $(seq 1 60); do
    if tail -n +"$((n + 1))" "$PAPER_DIR/console.log" | grep -q "Saved the game"; then flushed=""; break; fi
    sleep 1
  done
  trap 'tmux send-keys -t paper "save-on" Enter' EXIT
fi

ITEMS="world world_nether world_the_end server.properties ops.json whitelist.json
banned-ips.json banned-players.json bukkit.yml spigot.yml commands.yml help.yml
permissions.yml config"
mkdir -p "$SAVE"
for item in $ITEMS; do
  rm -rf "${SAVE:?}/$item"
  [ -e "$PAPER_DIR/$item" ] && cp -a "$PAPER_DIR/$item" "$SAVE/$item"
done
# plugins/ 는 Paper 가 다시 만드는 .paper-remapped 캐시를 빼고 복사
rm -rf "${SAVE:?}/plugins"
mkdir -p "$SAVE/plugins"
for p in "$PAPER_DIR"/plugins/*; do [ -e "$p" ] && cp -a "$p" "$SAVE/plugins/"; done
find "$SAVE" -name session.lock -delete

if $running; then tmux send-keys -t paper "save-on" Enter; trap - EXIT; fi

cd "$REPO"
git add -A -- paper-server/save
if git diff --cached --quiet -- paper-server/save; then
  echo "백업: 변경 없음"; exit 0
fi
git commit -q -m "Paper 서버 월드 백업 $(date -u '+%F %H:%M') UTC $flushed

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DkbvTSVVwr2W9NAa67TaEa" -- paper-server/save

for delay in 2 4 8 16; do
  if out=$(git push -q -u origin "$BRANCH" 2>&1); then
    echo "백업: 커밋·푸시 완료 $(git rev-parse --short HEAD) $flushed"; exit 0
  fi
  if echo "$out" | grep -qE "rejected|fetch first|non-fast-forward"; then
    git fetch -q origin "$BRANCH" && git merge -q --no-edit FETCH_HEAD
  fi
  sleep "$delay"
done
echo "백업: 커밋은 했지만 푸시 실패 — $(echo "$out" | tail -1)"
exit 1

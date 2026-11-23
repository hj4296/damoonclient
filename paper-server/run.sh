#!/bin/bash
# Paper 1.21.11 서버 + 인증 중계기를 tmux 백그라운드로 띄운다. 여러 번 실행해도 안전.
# 서버 폴더에 월드가 없으면 save/ 백업에서 복원한다.
#   콘솔 보기:  tmux attach -t paper   (빠져나오기: Ctrl+B 다음 D)
#   명령 보내기: tmux send-keys -t paper "say hi" Enter
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
export PAPER_DIR="${PAPER_DIR:-/home/user/paper-server}"
mkdir -p "$PAPER_DIR"
cd "$PAPER_DIR"

JAR=paper-1.21.11-132.jar
SHA=5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
if [ ! -f "$JAR" ]; then
  curl -sS -o "$JAR" "https://fill-data.papermc.io/v1/objects/$SHA/$JAR"
fi
echo "$SHA  $JAR" | sha256sum -c --quiet
[ -f eula.txt ] || echo "eula=true" > eula.txt

# 월드 복원 (서버가 꺼져 있고 서버 폴더에 월드가 없을 때만)
if ! tmux has-session -t paper 2>/dev/null && [ ! -d world ] && [ -d "$HERE/save/world" ]; then
  cp -a "$HERE/save/." "$PAPER_DIR/"
  echo "save/ 백업에서 월드·설정 복원함"
fi

if ! grep -q "sessionserver.mojang.com" /etc/hosts; then
  cat >> /etc/hosts <<'EOF'
# Paper 서버 인증 중계 (authproxy.py)
127.0.0.2 sessionserver.mojang.com
127.0.0.3 api.minecraftservices.com
127.0.0.4 api.mojang.com
EOF
fi

tmux has-session -t authproxy 2>/dev/null || \
  tmux new-session -d -s authproxy "python3 -I $HERE/authproxy.py 2>&1 | tee -a $PAPER_DIR/authproxy.log"
sleep 1
tmux has-session -t paper 2>/dev/null || \
  tmux new-session -d -s paper "PAPER_DIR=$PAPER_DIR $HERE/start.sh 2>&1 | tee -a $PAPER_DIR/console.log"
tmux ls

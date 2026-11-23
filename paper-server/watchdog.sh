#!/bin/bash
# 1분마다 check.sh(꺼져 있으면 재시작), 30분마다 backup.sh(월드 커밋·푸시). 결과는 watchdog.log 에 기록.
# 기록의 boot= 값이 바뀌면 그 사이에 컨테이너가 재시작된 것이고, 마지막 기록 시각이 멈춘 시점이다.
HERE="$(cd "$(dirname "$0")" && pwd)"
PAPER_DIR="${PAPER_DIR:-/home/user/paper-server}"
LOG="$PAPER_DIR/watchdog.log"
BACKUP_EVERY=${BACKUP_EVERY:-1800}
last_backup=0
echo "$(date '+%F %T') | boot=$(uptime -s) | watchdog 시작 (pid $$)" >> "$LOG"
while true; do
  echo "$(date '+%F %T') | boot=$(uptime -s) | $("$HERE/check.sh" 2>&1 | tail -1)" >> "$LOG"
  now=$(date +%s)
  if [ $((now - last_backup)) -ge "$BACKUP_EVERY" ]; then
    echo "$(date '+%F %T') | boot=$(uptime -s) | $("$HERE/backup.sh" 2>&1 | tail -1)" >> "$LOG"
    last_backup=$now
  fi
  sleep 60
done

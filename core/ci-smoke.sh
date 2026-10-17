#!/usr/bin/env bash
# 가상 화면(Xvfb)에서 개발용 클라이언트를 실행하고 Damoon 기능을 차례로 눌러 보며 스크린샷을 찍습니다.
# 사용법: ci-smoke.sh fabric|forge   (각 모드 폴더 안에서 실행)
set -u
MODE="$1"
export DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1

sudo apt-get install -y -qq xdotool ffmpeg > /dev/null
mkdir -p run
if [ "$MODE" = fabric ]; then
  printf 'onboardAccessibility:false\nlang:ko_kr\nskipMultiplayerWarning:true\ntutorialStep:none\n' > run/options.txt
  GARGS="${GARGS:-}"
else
  printf 'lang:ko_KR\n' > run/options.txt
  GARGS=""
fi

Xvfb :99 -screen 0 1280x720x24 &
sleep 2
(timeout 600 gradle runClient $GARGS --no-daemon > run.log 2>&1 &)

LOG=run/logs/latest.log
wait_for() { # 패턴, 최대 초
  for _ in $(seq 1 $(( $2 / 5 ))); do
    sleep 5
    grep -q "$1" "$LOG" run.log 2>/dev/null && return 0
    grep -q "Crash report saved\|---- Minecraft Crash Report\|BUILD FAILED" run.log "$LOG" 2>/dev/null && return 1
  done
  return 1
}
geom() { eval "$(xdotool search --name 'Minecraft' 2>/dev/null | head -1 | xargs -r xdotool getwindowgeometry --shell)"; WS=$((WIDTH / 2)); HS=$((HEIGHT / 2)); }
click() { geom; xdotool mousemove $((X + $1 * 2)) $((Y + $2 * 2)) click 1; }   # GUI 배율 2 기준 좌표
shot() { ffmpeg -loglevel error -y -f x11grab -video_size 1280x720 -i :99 -frames:v 1 "$1.png"; echo "shot $1"; }

if [ "$MODE" = fabric ]; then READY="Sound engine started\|Created: .*textures/atlas/gui"; else READY="Forge Mod Loader has successfully loaded\|Damoon Core .* 로드 완료"; fi
wait_for "$READY" 400 || echo "준비 신호를 못 찾음"
sleep 25
geom; echo "window X=$X Y=$Y W=$WIDTH H=$HEIGHT"
shot 1-title

# 1) Damoon 설정 버튼
if [ "$MODE" = fabric ]; then click $((WS / 2)) $((HS / 2 + 62)); else click $((WS / 2 - 51)) $((HS / 2 + 62)); fi
sleep 3; shot 2-damoon-menu
xdotool key Escape; sleep 2

# 2) 싱글플레이 → 새 월드 만들기
click $((WS / 2)) $((HS / 2 + 14)); sleep 5; shot 3-singleplayer
if [ "$MODE" = fabric ]; then
  click $((WS / 2 - 79)) $((HS - 17))            # 새로운 세계 만들기 (CreateWorldScreen 하단 왼쪽)
else
  click $((WS / 2 + 79)) $((HS - 42)); sleep 3    # 목록 화면의 "새로운 세계 생성"
  shot 3b-create
  click $((WS / 2 - 80)) $((HS - 18))            # 생성 화면의 "새로운 세계 생성"
fi
wait_for "joined the game\|logged in with entity id" 240 || echo "월드 진입 신호를 못 찾음"
sleep 20
shot 4-ingame-hud

# 3) 오른쪽 Shift → Damoon 메뉴
xdotool key Shift_R; sleep 3; shot 5-rshift-menu
xdotool key Escape; sleep 2

# 4) 줌 (C 누르고 있기)
xdotool keydown c; sleep 3; shot 6-zoom; xdotool keyup c; sleep 1

pkill -f 'KnotClient\|devlaunchinjector\|GradleStart\|launchwrapper\|architectury' || true
sleep 3
cp -r run/crash-reports . 2>/dev/null || true
exit 0

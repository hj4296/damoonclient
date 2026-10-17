#!/usr/bin/env bash
# 가상 화면(Xvfb)에서 게임을 실행하고 Damoon 기능을 차례로 눌러 보며 스크린샷을 찍습니다.
# 사용법: ci-smoke.sh fabric|forge|plain
#   LAUNCH  : 게임을 띄우는 명령 (기본: gradle runClient)
#   LOGFILE : 게임 로그 경로 (기본: run/logs/latest.log, e2e-logfile.txt 가 있으면 그 내용)
MODE="$1"
export DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 __GLX_VENDOR_LIBRARY_NAME=mesa MESA_GL_VERSION_OVERRIDE=4.6

sudo apt-get install -y -qq xdotool ffmpeg x11-xserver-utils > /dev/null

if [ -z "${LAUNCH:-}" ]; then
  mkdir -p run
  if [ "$MODE" = fabric ]; then
    printf 'onboardAccessibility:false\nlang:ko_kr\nskipMultiplayerWarning:true\ntutorialStep:none\n' > run/options.txt
  else
    printf 'lang:ko_KR\n' > run/options.txt
  fi
  LAUNCH="gradle runClient ${GARGS:-} --no-daemon"
fi

Xvfb :99 -screen 0 1280x720x24 +extension GLX +extension RANDR +render -noreset &
sleep 2
(timeout 900 bash -c "$LAUNCH" > run.log 2>&1 &)

logfile() {
  if [ -n "${LOGFILE:-}" ]; then echo "$LOGFILE"
  elif [ -f e2e-logfile.txt ]; then cat e2e-logfile.txt
  else echo run/logs/latest.log; fi
}
wait_for() { # 패턴, 최대 초
  for _ in $(seq 1 $(( $2 / 5 ))); do
    sleep 5
    grep -q "$1" "$(logfile)" run.log 2>/dev/null && return 0
    grep -q "Crash report saved\|---- Minecraft Crash Report\|BUILD FAILED\|E2E 실패" run.log "$(logfile)" 2>/dev/null && return 1
  done
  return 1
}
geom() { X=0; Y=0; WIDTH=854; HEIGHT=480; eval "$(xdotool search --name 'Minecraft' 2>/dev/null | head -1 | xargs -r xdotool getwindowgeometry --shell)"; WS=$((WIDTH / 2)); HS=$((HEIGHT / 2)); }
click() { geom; xdotool mousemove $((X + $1 * 2)) $((Y + $2 * 2)) click 1; }   # GUI 배율 2 기준 좌표
shot() { ffmpeg -loglevel error -y -f x11grab -video_size 1280x720 -i :99 -frames:v 1 "$1.png"; echo "shot $1"; }

READY="Sound engine started\|Forge Mod Loader has successfully loaded\|Created: .*textures/atlas/gui\|Damoon Core .* 로드 완료"
wait_for "$READY" 840 || echo "준비 신호를 못 찾음"
sleep 30
geom; echo "window X=$X Y=$Y W=$WIDTH H=$HEIGHT"
shot 1-title

if [ "$MODE" != plain ]; then
  # 1) Damoon 설정 버튼
  if [ "$MODE" = forge ]; then click $((WS / 2 - 51)) $((HS / 2 + 62)); else click $((WS / 2)) $((HS / 2 + 62)); fi
  sleep 3; shot 2-damoon-menu
  xdotool key Escape; sleep 2

  # 2) 싱글플레이 → 새 월드 만들기
  click $((WS / 2)) $((HS / 2 + 14)); sleep 5; shot 3-singleplayer
  if [ "$MODE" = fabric17 ]; then
    # 게임 모드: 서바이벌 → 하드코어 → 크리에이티브 (명령어 허용)
    click $((WS / 2)) $((HS / 2 - 34)); sleep 1; click $((WS / 2)) $((HS / 2 - 34)); sleep 1
    shot 3a-creative
    click $((WS / 2 - 79)) $((HS - 17))
  elif [ "$MODE" = fabric ]; then
    click $((WS / 2 - 79)) $((HS - 17))
  else
    click $((WS / 2 + 79)) $((HS - 42)); sleep 3
    shot 3b-create
    click $((WS / 2 - 80)) $((HS - 18))
  fi
  wait_for "joined the game\|logged in with entity id" 300 || echo "월드 진입 신호를 못 찾음"
  sleep 25
  shot 4-ingame-hud

  # 3) 오른쪽 Shift → Damoon 메뉴
  xdotool key Shift_R; sleep 3; shot 5-rshift-menu
  xdotool key Escape; sleep 2

  # 4) 줌 (C 누르고 있기)
  xdotool keydown c; sleep 3; shot 6-zoom; xdotool keyup c; sleep 1

  # 5) 1.7 애니메이션: 칼 받기 → 우클릭 유지(막기) → 막은 채로 좌클릭(블록히트)
  if [ "$MODE" = fabric17 ]; then
    xdotool key t; sleep 2; xdotool type --delay 40 "/give @s diamond_sword"; sleep 1; xdotool key Return; sleep 3
    xdotool mousemove_relative --sync 0 200 2>/dev/null || true
    shot 7-sword
    xdotool mousedown 3; sleep 2; shot 8-sword-block
    (ffmpeg -loglevel error -y -f x11grab -framerate 8 -video_size 1280x720 -i :99 -t 1.5 9-blockhit-%02d.png &)
    sleep 0.2; xdotool click 1; sleep 2
    xdotool mouseup 3; sleep 1
    xdotool click 1; sleep 0.15; shot 10-swing-no-dip
  fi
fi

cp "$(logfile)" game.log 2>/dev/null || true
cp -r "$(dirname "$(logfile)")/../crash-reports" . 2>/dev/null || true
pkill -f 'KnotClient\|devlaunchinjector\|GradleStart\|launchwrapper\|net.minecraft.client.main.Main' || true
sleep 3
exit 0

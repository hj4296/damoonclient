# Paper 1.21.11 서버 (클라우드 세션용)

클라우드 세션 컨테이너에서 Paper 1.21.11(빌드 132)을 돌리고, 월드를 이 브랜치에 백업하는 스크립트입니다.
서버는 `/home/user/paper-server`(`PAPER_DIR`)에서 실행됩니다.

| 파일 | 역할 |
|---|---|
| `run.sh` | 서버 + 인증 중계기를 tmux로 실행. 월드가 없으면 `save/`에서 복원. 여러 번 실행해도 안전 |
| `check.sh` | 상태 점검(서버 목록 핑). 꺼져 있으면 `run.sh`로 재시작 |
| `backup.sh` | `save-off` → `save-all flush` → `save/`로 복사 → `save-on`, 바뀌었으면 커밋·푸시 |
| `watchdog.sh` | 1분마다 `check.sh`, 30분마다 `backup.sh` |
| `authproxy.py` | 마인크래프트 서버가 Mojang 인증 호스트로 프록시 없이 직접 접속하므로, `/etc/hosts`로 127.0.0.x에 돌린 뒤 HTTPS 프록시로 CONNECT 중계 |
| `start.sh` | JVM 옵션(6GB, Aikar 플래그) |
| `save/` | 월드(`world`, `world_nether`, `world_the_end`)와 서버 설정 백업 |

## 새 컨테이너에서 복원

```bash
git fetch origin ccr-7b5bb142-86ezy7 && git checkout ccr-7b5bb142-86ezy7
paper-server/run.sh      # Paper 다운로드 + save/ 에서 월드 복원 + 실행
```

필요한 네트워크 허용 호스트: `fill-data.papermc.io`, `piston-data.mojang.com`(첫 실행 때 바닐라 jar),
`sessionserver.mojang.com`, `api.minecraftservices.com`, `api.mojang.com`(정품 인증).

콘솔: `tmux attach -t paper` (빠져나오기: Ctrl+B 다음 D)

# 🌙 Damoon Client

마인크래프트 **1.8.9 ~ 26.3** 을 지원하는 데스크톱 런처입니다.
버전 설치, 모드로더 설치, 모드 검색·설치, 최적화까지 한 화면에서 처리합니다.

## Damoon Core (게임 안 기능)

**1.8.9 Forge**, **1.21.1 Fabric**, **1.21.11 Fabric**에서 자동으로 설치되는 Damoon 전용 모드입니다 (`core/` 폴더).

- Damoon 메인 메뉴, 오른쪽 Shift 메뉴(기능 켜기/끄기)
- HUD: FPS · CPS · 핑 · 좌표/방향 · 키스트로크 · 갑옷 · 포션, 마우스로 위치 편집
- 줌(C, 휠로 배율), 풀브라이트, 항상 달리기
- 1.7 애니메이션 (1.21.11): 칼 막기·블록히트, 공격 쿨다운 모션 제거

GitHub Actions(`core.yml`)가 빌드한 뒤 가상 화면에서 실제로 게임을 켜 메뉴·HUD·줌을 눌러 보고, 스크린샷을 `ci/<버전>` 브랜치에 올립니다.

## 주요 기능

| 기능 | 내용 |
|---|---|
| 버전 | Mojang 공식 목록에서 1.8.9 이후 모든 정식 버전(+ 선택 시 스냅샷)을 자동으로 불러옴. 26.4 같은 새 버전도 코드 수정 없이 표시 |
| 모드로더 | Fabric, Quilt, Forge, NeoForge, Legacy Fabric(1.8.9용 Fabric) — 선택한 버전에서 쓸 수 있는 로더만 활성화 |
| 모드 | Modrinth 검색 → 원클릭 설치, **필수 의존성 자동 설치**(Fabric API 등), 켜기/끄기, 삭제, 일괄 업데이트, .jar 드래그 앤 드롭 |
| 최적화 | 아래 "최적화" 참고 |
| 자바 | Mojang 공식 Java 런타임을 버전에 맞게 자동 설치 (1.8.9→Java 8, 1.20.5+→21, 26.x→25) |
| 계정 | Microsoft 정품 계정 로그인 (리프레시 토큰은 OS 보안 저장소로 암호화) |
| 인스턴스 | 인스턴스마다 모드·월드·설정이 분리, 메모리·해상도·자동 접속 서버·JVM 인자 개별 설정 |

### 버전별 로더 지원

| MC 버전 | 사용 가능 로더 |
|---|---|
| 1.8.9 ~ 1.12.2 | Forge, Legacy Fabric |
| 1.14 ~ 1.20.1 | Fabric, Quilt, Forge |
| 1.20.2 ~ 1.21.11 | Fabric, Quilt, Forge, NeoForge |
| 26.1 ~ 26.3 | Fabric, Quilt, NeoForge (+ Forge가 지원하는 경우) |

실제 지원 여부는 각 로더 서버에 실시간으로 조회해서 결정합니다.

### 최적화

- **최적화 팩 자동 설치** — 버전·로더에 맞는 파일이 있는 모드만 골라 설치하고, 없으면 건너뜁니다.
  - Fabric/Quilt: Sodium, Lithium, FerriteCore, EntityCulling, ImmediatelyFast, ModernFix, MoreCulling, Dynamic FPS, Krypton (+ 구버전은 Starlight, LazyDFU)
  - NeoForge: Sodium(없으면 Embeddium), FerriteCore, ModernFix, EntityCulling, ImmediatelyFast, Dynamic FPS
  - Forge: Embeddium/Rubidium, FerriteCore, ModernFix, EntityCulling, ImmediatelyFast, Dynamic FPS, **1.8.9는 Patcher**
- **GC 튜닝** — Java 21+ 이고 메모리 4GB 이상이면 세대별 ZGC(프레임 끊김 감소), 그 외에는 클라이언트용 G1 설정
- **메모리 자동 할당** — 시스템 RAM과 설치된 모드 수에 맞춰 권장값 계산 (RAM의 절반 이하)
- **빠른 실행** — 첫 실행에 모든 파일을 SHA1로 검증한 뒤, 다음부터는 크기만 비교해 바로 시작 ("파일 검증 후 실행"으로 전체 재검증)
- **병렬 다운로드** — 에셋 32개, 라이브러리 16개 동시 다운로드 + 재시도
- **초기 옵션** — 새 인스턴스에 한국어, 렌더 거리 10, 엔티티 그림자 끔, (1.8.9) VBO/FBO 켬

> OptiFine은 배포 라이선스상 자동 설치가 불가능합니다. 원하면 "파일에서 추가"로 직접 넣으세요.

## .exe 만들기

### 방법 1 — 내 PC에서 (가장 쉬움)
1. [Node.js](https://nodejs.org) LTS 설치
2. 이 폴더의 **`build-exe.bat` 더블클릭**
3. 끝나면 `dist` 폴더가 열립니다
   - `DamoonClient-Setup-1.0.0.exe` — 설치 프로그램 (바탕화면·시작 메뉴 바로가기 생성)
   - `DamoonClient-Portable-1.0.0.exe` — 설치 없이 바로 실행

### 방법 2 — GitHub에서 자동 빌드 (PC에 아무것도 설치 안 해도 됨)
1. GitHub에 새 저장소를 만들고 이 폴더 전체를 업로드
2. 저장소의 **Actions** 탭 → "Build Damoon Client" 실행이 자동으로 시작
3. 5~10분 뒤 실행 결과 아래 **Artifacts**에서 Windows/macOS/Linux 파일 다운로드
4. `v1.0.0` 같은 태그를 만들면 **Releases**에도 자동으로 올라갑니다

> 코드 서명이 없어서 처음 실행할 때 Windows SmartScreen 경고가 뜰 수 있습니다. "추가 정보 → 실행"을 누르면 됩니다.

## 개발 모드

```bash
npm install
npm start          # 개발 모드 실행
npm test           # 단위 테스트 (오프라인)
```

## 로그인 없이 플레이 (공식 런처 사용)

**공식 런처로 플레이** 버튼을 누르면 Damoon이 버전·모드로더·모드·최적화를 준비한 뒤 공식 Minecraft Launcher에 `Damoon · 인스턴스이름` 프로필을 추가합니다.
공식 런처 → Minecraft: Java Edition → 설치 버전에서 그 프로필로 플레이하면 됩니다. 로그인은 공식 런처 계정을 쓰므로 아래 Client ID 설정이 필요 없습니다.

## Microsoft 로그인 설정 (Damoon에서 직접 실행할 때만)

마인크래프트 계정 인증은 Microsoft에 등록된 앱 ID가 있어야 합니다.

1. [Azure Portal](https://portal.azure.com) → **앱 등록** → **새 등록**
   - 지원되는 계정 유형: **개인 Microsoft 계정만**
2. 등록한 앱 → **인증** → **퍼블릭 클라이언트 흐름 허용: 예** → 저장
3. **개요**의 *애플리케이션(클라이언트) ID* 복사
4. Mojang의 앱 검토 신청서([aka.ms/mce-reviewappid](https://aka.ms/mce-reviewappid))로 Minecraft API 사용 승인을 신청 — 승인 전에는 "Minecraft 서비스 인증 실패"가 납니다
5. Damoon Client → 왼쪽 아래 ⚙ → Client ID 입력 → 저장 → 로그인

정품(Java Edition) 계정만 게임을 실행할 수 있습니다.

## 폴더 구조

```
데이터 폴더 (Windows: %APPDATA%\.damoon, macOS: ~/Library/Application Support/damoon, Linux: ~/.damoon)
├── versions/     버전 JSON·jar (인스턴스끼리 공유 → 디스크 절약)
├── libraries/    라이브러리 (공유)
├── assets/       에셋 (공유)
├── runtime/      자동 설치된 Java
└── instances/<이름>/   인스턴스별 mods, saves, config, options.txt
```

## 코드 구조

```
main.js                  Electron 메인 프로세스 (IPC)
preload.js               화면 ↔ 메인 브리지
src/core/
  versions.js            버전 목록, 버전 JSON 상속 병합
  install.js             클라이언트·라이브러리·네이티브·에셋 설치
  java.js                Java 런타임 자동 설치
  launch.js              실행 인자 생성 (구형 minecraftArguments / 신형 arguments 모두)
  optimize.js            GC 튜닝, 메모리 계산, 최적화 모드 프리셋
  mods.js                Modrinth 검색·설치·의존성·업데이트
  auth.js                Microsoft → Xbox → Minecraft 인증
  instances.js           인스턴스 관리, 설치→실행 흐름
  loaders/fabric.js      Fabric / Quilt / Legacy Fabric
  loaders/forge.js       Forge / NeoForge (설치기 프로세서 직접 실행)
src/renderer/            화면 (HTML/CSS/JS)
```

## 알려진 제한

- Apple Silicon Mac에서 1.8.9~1.18 은 x64 Java(Rosetta)로 실행됩니다.
- Forge 1.13~1.16 초기 빌드 중 일부는 설치기 형식이 달라 실패할 수 있습니다. 이 경우 같은 버전의 다른 Forge 빌드를 선택하세요.
- 이 저장소의 테스트는 오프라인 로직만 검사합니다. 실제 다운로드·실행은 각 OS에서 직접 확인하세요.

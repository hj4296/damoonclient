@echo off
chcp 65001 > nul
title Damoon Client - EXE 빌드
cd /d "%~dp0"

echo ==============================================
echo    Damoon Client .exe 만들기
echo ==============================================
echo.

where node > nul 2>&1
if errorlevel 1 (
  echo [!] Node.js가 설치되어 있지 않습니다.
  echo     https://nodejs.org 에서 LTS 버전을 설치한 뒤 이 파일을 다시 실행하세요.
  start https://nodejs.org
  pause
  exit /b 1
)

for /f "tokens=*" %%v in ('node -v') do echo Node.js %%v 확인됨
echo.

echo [1/2] 필요한 패키지 설치 중... (처음 한 번은 몇 분 걸립니다)
call npm install
if errorlevel 1 (
  echo [!] 패키지 설치 실패. 인터넷 연결을 확인하세요.
  pause
  exit /b 1
)

echo.
echo [2/2] .exe 빌드 중...
call npm run dist:win
if errorlevel 1 (
  echo [!] 빌드 실패. 위 오류 메시지를 확인하세요.
  pause
  exit /b 1
)

echo.
echo ==============================================
echo  완료! dist 폴더에 두 파일이 생겼습니다.
echo   - DamoonClient-Setup-1.1.0.exe     (설치 프로그램)
echo   - DamoonClient-Portable-1.1.0.exe  (설치 없이 바로 실행)
echo ==============================================
start "" "%~dp0dist"
pause

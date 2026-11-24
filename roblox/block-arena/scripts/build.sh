#!/usr/bin/env bash
# 플레이스 파일을 만들고 검사합니다. 필요한 도구: rojo, selene, lune (cargo install rojo selene lune)
# API_DUMP 에 Roblox API-Dump.json 경로를 주면 API 이름 검사도 합니다.
set -euo pipefail
cd "$(dirname "$0")/.."
rojo build default.project.json -o BlockArena.rbxlx
selene src
lune run tests/run.luau BlockArena.rbxlx
if [ -n "${API_DUMP:-}" ]; then
	python3 tests/api_check.py "$API_DUMP"
fi

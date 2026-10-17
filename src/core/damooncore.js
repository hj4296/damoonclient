'use strict';
// Damoon Core(게임 안 기능 모드) 자동 설치
//   메인 메뉴 · 오른쪽 Shift 메뉴 · HUD(FPS/CPS/키스트로크/좌표/핑/갑옷/포션) · 줌 · 풀브라이트 · 항상 달리기
const fs = require('fs');
const path = require('path');
const { download } = require('./http');
const mods = require('./mods');

const RELEASE_BASE = process.env.DAMOON_CORE_URL || 'https://github.com/hj4296/damoonclient/releases/latest/download/';

// "로더@MC버전" → 릴리스 파일 이름
const SUPPORTED = {
  'forge@1.8.9': 'damoon-core-forge-1.8.9.jar',
  'fabric@1.21.1': 'damoon-core-fabric-1.21.1.jar',
};

function coreFile(loader, mc) { return SUPPORTED[`${loader}@${mc}`] || null; }
function supportedList() { return Object.keys(SUPPORTED).map(k => { const [loader, mc] = k.split('@'); return { loader, mc }; }); }

// 설치 또는 하루 한 번 최신 버전 확인
async function ensure(inst, gameDir, onLog = () => {}) {
  const file = coreFile(inst.loader, inst.mc);
  if (!file || inst.damoonCore === false) return false;
  const dir = path.join(gameDir, 'mods');
  fs.mkdirSync(dir, { recursive: true });
  const dest = path.join(dir, file);
  const disabled = fs.existsSync(dest + '.disabled');
  const fresh = fs.existsSync(dest) && Date.now() - fs.statSync(dest).mtimeMs < 24 * 3600 * 1000;

  if (!disabled && !fresh) {
    const tmp = dest + '.new';
    try {
      fs.rmSync(tmp, { force: true });
      await download(RELEASE_BASE + file, tmp);
      fs.renameSync(tmp, dest);
      onLog(`✦ Damoon Core 설치/업데이트 완료 (${file})`);
    } catch (e) {
      fs.rmSync(tmp, { force: true });
      if (fs.existsSync(dest)) onLog(`✦ Damoon Core 업데이트 확인 실패, 기존 파일 사용: ${e.message}`);
      else { onLog(`✦ Damoon Core를 받지 못했습니다: ${e.message}`); return false; }
    }
  }
  // Fabric 버전은 Fabric API 필요
  if (inst.loader === 'fabric') {
    const hasApi = mods.list(gameDir).some(m => /fabric-api/i.test(m.file) || /^Fabric API$/i.test(m.title));
    if (!hasApi) await mods.install('fabric-api', { gameDir, mc: inst.mc, loader: inst.loader, onLog });
  }
  return true;
}

module.exports = { ensure, coreFile, supportedList, SUPPORTED };

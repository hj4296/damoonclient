'use strict';
// CI 전용 끝-끝 테스트: Damoon 런처 코드로 실제 버전·로더를 설치하고 게임을 실행합니다.
// 사용법: node test/e2e-launch.js <loader> <mc> [damoon-core.jar]
// ※ 테스트용 오프라인 이름으로 싱글플레이만 확인합니다 (CI 가상 머신 전용, 배포용 기능 아님).
const fs = require('fs');
const path = require('path');

process.env.DAMOON_HOME = path.resolve(process.env.E2E_HOME || 'e2e-home');
const { P } = require('../src/core/paths');
const instances = require('../src/core/instances');
const versions = require('../src/core/versions');
const mods = require('../src/core/mods');
const { installVersion } = require('../src/core/install');
const { buildArgs, launch, redact } = require('../src/core/launch');

const [loader, mc, coreJar] = process.argv.slice(2);
const log = s => process.stdout.write(s.endsWith('\n') ? s : s + '\n');
let lastPct = -1;
const progress = p => {
  const pct = p.total ? Math.floor((p.done / p.total) * 10) : 0;
  if (pct !== lastPct || p.done === p.total) { lastPct = pct; log(`[진행] ${p.label} ${p.done}/${p.total}`); }
};

(async () => {
  let inst = instances.create({ name: `e2e ${loader} ${mc}`, mc, loader, optimize: false });
  inst = instances.save({ ...inst, damoonCore: false }); // 방금 빌드한 jar를 직접 넣음
  const gameDir = P.instanceDir(inst.id);
  fs.mkdirSync(path.join(gameDir, 'mods'), { recursive: true });
  if (coreJar) {
    fs.copyFileSync(coreJar, path.join(gameDir, 'mods', path.basename(coreJar)));
    log(`Damoon Core 넣음: ${path.basename(coreJar)}`);
  }

  const prep = await instances.prepare(inst.id, { onLog: log, onProgress: progress });
  if (loader === 'fabric') await mods.install('fabric-api', { gameDir, mc, loader, onLog: log });

  const version = await versions.resolveVersion(prep.inst.versionId);
  const installed = await installVersion(version, gameDir, progress);
  const args = buildArgs({
    version, install: installed, gameDir, memoryMb: 3072,
    account: { name: 'DamoonCI', uuid: '00000000000000000000000000000000', accessToken: '0', xuid: '0' },
  });
  log(`LOGFILE=${path.join(gameDir, 'logs', 'latest.log')}`);
  fs.writeFileSync(path.resolve('e2e-logfile.txt'), path.join(gameDir, 'logs', 'latest.log'));
  log(`실행: ${prep.java} ${redact(args).join(' ')}`);
  const child = launch(prep.java, args, gameDir, s => process.stdout.write(s));
  child.on('exit', code => { log(`게임 종료 코드: ${code}`); process.exit(0); });
})().catch(e => { console.error('E2E 실패:', e); process.exit(1); });

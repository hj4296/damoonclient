'use strict';
// 공식 Minecraft Launcher로 내보내기
//   Damoon이 버전·모드로더·모드·최적화를 준비하고, 공식 런처에 "Damoon · 이름" 프로필을 추가합니다.
//   로그인과 실행은 공식 런처가 처리하므로 Azure Client ID가 필요 없습니다.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { P, ensureDir } = require('./paths');
const versions = require('./versions');
const { planLibraries } = require('./install');
const { downloadAll } = require('./http');
const { jvmFlags, recommendedMemory } = require('./optimize');
const instances = require('./instances');
const mods = require('./mods');

function officialDir() {
  if (process.env.DAMOON_OFFICIAL_DIR) return process.env.DAMOON_OFFICIAL_DIR;
  switch (process.platform) {
    case 'win32': return path.join(process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'), '.minecraft');
    case 'darwin': return path.join(os.homedir(), 'Library', 'Application Support', 'minecraft');
    default: return path.join(os.homedir(), '.minecraft');
  }
}

function iconDataUri() {
  try { return 'data:image/png;base64,' + fs.readFileSync(path.join(__dirname, '..', 'assets', 'icon-128.png')).toString('base64'); }
  catch { return 'Furnace'; }
}

// inheritsFrom 체인 중 바닐라를 제외한 버전 JSON 목록 (공식 런처가 바닐라는 직접 받음)
function loaderChain(versionId, manifestIds) {
  const chain = [];
  let id = versionId;
  while (id) {
    const file = P.versionJson(id);
    if (!fs.existsSync(file)) break;
    const json = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (!json.inheritsFrom && manifestIds.has(id)) break;
    chain.push({ id, file });
    id = json.inheritsFrom;
  }
  return chain;
}

function copyIfMissing(src, dest) {
  if (!fs.existsSync(src)) return false;
  if (fs.existsSync(dest) && fs.statSync(dest).size === fs.statSync(src).size) return false;
  ensureDir(path.dirname(dest));
  fs.copyFileSync(src, dest);
  return true;
}

async function exportToOfficial(id, { onProgress = () => {}, onLog = () => {} } = {}) {
  const { inst, gameDir } = await instances.prepare(id, { onProgress, onLog, needJava: false });
  const mc = officialDir();
  ensureDir(mc);

  // 1) 로더 라이브러리를 Damoon 쪽에 받아 두고 공식 런처 폴더로 복사
  //    (Forge 프로세서가 만든 패치 jar처럼 인터넷에 없는 파일도 함께 옮겨야 함)
  const version = await versions.resolveVersion(inst.versionId);
  const plan = planLibraries(version);
  await downloadAll(plan.tasks, { onProgress, label: '라이브러리', quick: true });
  let copied = 0;
  for (const file of [...plan.classpath, ...plan.natives.map(n => n.file)]) {
    const rel = path.relative(P.libraries, file);
    if (rel.startsWith('..')) continue;
    if (copyIfMissing(file, path.join(mc, 'libraries', rel))) copied++;
  }
  onLog(`라이브러리 ${copied}개 복사\n`);

  // 2) 로더 버전 JSON 복사
  const manifest = await versions.getManifest();
  const ids = new Set(manifest.versions.map(v => v.id));
  for (const v of loaderChain(inst.versionId, ids)) {
    ensureDir(path.join(mc, 'versions', v.id));
    fs.copyFileSync(v.file, path.join(mc, 'versions', v.id, `${v.id}.json`));
    onLog(`버전 등록: ${v.id}\n`);
  }

  // 3) launcher_profiles.json 에 프로필 추가/갱신
  const profFile = path.join(mc, 'launcher_profiles.json');
  let profiles = { profiles: {}, settings: {}, version: 3 };
  try { profiles = JSON.parse(fs.readFileSync(profFile, 'utf8')); } catch { /* 처음이면 새로 만듦 */ }
  profiles.profiles = profiles.profiles || {};
  const key = `damoon-${inst.id}`;
  const now = new Date().toISOString();
  const memoryMb = inst.memoryMb || recommendedMemory(inst.mc, mods.list(gameDir).length);
  const javaArgs = [...jvmFlags(version, memoryMb), ...(inst.jvmArgs || '').split(/\s+/).filter(Boolean)].join(' ');
  profiles.profiles[key] = {
    ...(profiles.profiles[key] || { created: now }),
    name: `Damoon · ${inst.name}`,
    type: 'custom',
    lastVersionId: inst.versionId,
    gameDir,
    javaArgs,
    icon: iconDataUri(),
    lastUsed: now,
    ...(inst.width && inst.height ? { resolution: { width: inst.width, height: inst.height } } : {}),
  };
  if (fs.existsSync(profFile)) fs.copyFileSync(profFile, profFile + '.damoon-backup');
  fs.writeFileSync(profFile, JSON.stringify(profiles, null, 2));
  onLog(`공식 런처 프로필 추가: Damoon · ${inst.name}\n`);
  instances.save({ ...inst, exportedAt: Date.now() });
  return { profileName: `Damoon · ${inst.name}`, officialDir: mc };
}

module.exports = { exportToOfficial, officialDir, loaderChain };

'use strict';
// 인스턴스(프로필) 관리 + 설치·실행 오케스트레이션
const fs = require('fs');
const path = require('path');
const { P, ensureDir } = require('./paths');
const { download } = require('./http');
const versions = require('./versions');
const { installVersion } = require('./install');
const { ensureJava, consoleJava } = require('./java');
const fabric = require('./loaders/fabric');
const forge = require('./loaders/forge');
const mods = require('./mods');
const damooncore = require('./damooncore');
const { recommendedMemory, writeDefaultOptions } = require('./optimize');
const { buildArgs, launch, redact } = require('./launch');

const LOADERS = ['vanilla', 'fabric', 'quilt', 'forge', 'neoforge', 'legacy-fabric'];
const LOADER_NAMES = { vanilla: '바닐라', fabric: 'Fabric', quilt: 'Quilt', forge: 'Forge', neoforge: 'NeoForge', 'legacy-fabric': 'Legacy Fabric' };

function slug(s) {
  return (s || 'instance').toLowerCase().replace(/[^a-z0-9가-힣._-]+/g, '-').replace(/^-+|-+$/g, '') || 'instance';
}

function list() {
  ensureDir(P.instances);
  return fs.readdirSync(P.instances)
    .map(id => { try { return JSON.parse(fs.readFileSync(path.join(P.instanceDir(id), 'instance.json'), 'utf8')); } catch { return null; } })
    .filter(Boolean)
    .sort((a, b) => (b.lastPlayed || b.created || 0) - (a.lastPlayed || a.created || 0));
}

function get(id) { return JSON.parse(fs.readFileSync(path.join(P.instanceDir(id), 'instance.json'), 'utf8')); }
function save(inst) {
  ensureDir(P.instanceDir(inst.id));
  fs.writeFileSync(path.join(P.instanceDir(inst.id), 'instance.json'), JSON.stringify(inst, null, 2));
  return inst;
}

function create({ name, mc, loader = 'vanilla', loaderVersion = null, optimize = true }) {
  if (!LOADERS.includes(loader)) throw new Error(`지원하지 않는 로더: ${loader}`);
  let id = slug(name || `${mc}-${loader}`);
  while (fs.existsSync(P.instanceDir(id))) id += '-2';
  const inst = {
    id, name: name || `${mc} ${LOADER_NAMES[loader]}`, mc, loader, loaderVersion,
    versionId: loader === 'vanilla' ? mc : null,
    optimize, damoonCore: true, memoryMb: recommendedMemory(mc), jvmArgs: '', javaPath: '',
    width: 0, height: 0, server: '', created: Date.now(), lastPlayed: 0,
  };
  save(inst);
  writeDefaultOptions(P.instanceDir(id), mc);
  return inst;
}

function update(id, patch) {
  const inst = { ...get(id), ...patch, id };
  // 로더나 버전이 바뀌면 다시 설치하도록 versionId 초기화
  if ('mc' in patch || 'loader' in patch || 'loaderVersion' in patch) inst.versionId = inst.loader === 'vanilla' ? inst.mc : null;
  return save(inst);
}

function remove(id) { fs.rmSync(P.instanceDir(id), { recursive: true, force: true }); }

// 해당 MC 버전에서 사용 가능한 로더와 추천 버전
async function availableLoaders(mc) {
  const ge = (v) => versions.compareMc(mc, v) >= 0;
  const checks = {
    vanilla: async () => [{ version: mc, stable: true }],
    fabric: async () => ge('1.14') ? fabric.listLoaderVersions('fabric', mc).catch(() => []) : [],
    quilt: async () => ge('1.14') ? fabric.listLoaderVersions('quilt', mc).catch(() => []) : [],
    'legacy-fabric': async () => !ge('1.14') ? fabric.listLoaderVersions('legacy-fabric', mc).catch(() => []) : [],
    forge: async () => forge.listForgeVersions(mc).catch(() => []),
    neoforge: async () => forge.listNeoForgeVersions(mc).catch(() => []),
  };
  const out = {};
  await Promise.all(Object.entries(checks).map(async ([k, fn]) => { out[k] = (await fn()).slice(0, 30); }));
  return out;
}

// 로더 설치 (필요할 때만)
async function ensureLoader(inst, java, onProgress, onLog) {
  if (inst.versionId && fs.existsSync(P.versionJson(inst.versionId))) return inst;
  let r;
  switch (inst.loader) {
    case 'vanilla': r = { versionId: inst.mc, loaderVersion: inst.mc }; break;
    case 'fabric': case 'quilt': case 'legacy-fabric':
      r = await fabric.install(inst.loader, inst.mc, inst.loaderVersion); break;
    case 'forge': r = await forge.installForge(inst.mc, inst.loaderVersion, consoleJava(java), onProgress, onLog); break;
    case 'neoforge': r = await forge.installNeoForge(inst.mc, inst.loaderVersion, consoleJava(java), onProgress, onLog); break;
  }
  onLog(`${LOADER_NAMES[inst.loader]} ${r.loaderVersion} 설치 완료\n`);
  return save({ ...inst, versionId: r.versionId, loaderVersion: r.loaderVersion });
}

// 설치 → 최적화 → 실행
async function play(id, account, { onProgress = () => {}, onLog = () => {}, onExit = () => {}, verify = false } = {}) {
  const { inst, gameDir, java } = await prepare(id, { onProgress, onLog });
  const version = await versions.resolveVersion(inst.versionId);
  const installed = await installVersion(version, gameDir, onProgress, { verify });
  const args = buildArgs({
    version, install: installed, account, gameDir,
    memoryMb: inst.memoryMb || recommendedMemory(inst.mc, mods.list(gameDir).length),
    width: inst.width, height: inst.height,
    extraJvm: (inst.jvmArgs || '').split(/\s+/).filter(Boolean),
    server: inst.server || null,
  });
  onLog(`실행: ${java} ${redact(args).join(' ')}\n\n`);
  save({ ...inst, lastPlayed: Date.now() });

  const child = launch(java, args, gameDir, onLog);
  child.on('exit', code => onExit(code));
  return child;
}

// 자바 + 로더 + 최적화 모드까지 준비 (직접 실행 / 공식 런처 내보내기 공용)
async function prepare(id, { onProgress = () => {}, onLog = () => {}, needJava = true } = {}) {
  let inst = get(id);
  const gameDir = P.instanceDir(id);

  onLog(`== Damoon Client: ${inst.name} (${inst.mc}, ${LOADER_NAMES[inst.loader]}) ==\n`);
  const vanilla = await versions.resolveVersion(inst.mc);
  // 공식 런처로 내보낼 때는 Forge/NeoForge 설치(프로세서 실행)에만 자바가 필요
  const loaderNeedsJava = ['forge', 'neoforge'].includes(inst.loader) && !(inst.versionId && fs.existsSync(P.versionJson(inst.versionId)));
  let java = null;
  if (needJava || loaderNeedsJava) {
    java = await ensureJava(vanilla, onProgress, { override: inst.javaPath });
    onLog(`Java: ${java}\n`);
  }

  // Forge 계열 프로세서는 바닐라 jar가 먼저 필요
  if (vanilla.downloads?.client) await download(vanilla.downloads.client.url, P.versionJar(inst.mc), { sha1: vanilla.downloads.client.sha1 });
  inst = await ensureLoader(inst, java, onProgress, onLog);

  // 최초 1회 최적화 모드팩 설치
  if (inst.optimize && !inst.optimizedOnce && inst.loader !== 'vanilla') {
    onLog('최적화 모드 설치 중...\n');
    try {
      const done = await mods.installOptimizationPack({ gameDir, mc: inst.mc, loader: inst.loader, onLog: s => onLog(s + '\n') });
      onLog(`최적화 모드 ${done.length}개 설치\n`);
    } catch (e) { onLog(`최적화 모드 설치 일부 실패: ${e.message}\n`); }
    inst = save({ ...inst, optimizedOnce: true });
  }

  // Damoon Core (게임 안 기능) — 지원 버전이면 자동 설치
  try { await damooncore.ensure(inst, gameDir, s => onLog(s + '\n')); }
  catch (e) { onLog(`Damoon Core 설치 실패: ${e.message}\n`); }
  return { inst, gameDir, java };
}

module.exports = { LOADERS, LOADER_NAMES, list, get, create, update, remove, save, availableLoaders, play, prepare, ensureLoader };

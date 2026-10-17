'use strict';
// Forge (1.8.9 ~ ) / NeoForge (1.20.2 ~ 26.x) 설치
//  - 구형 설치기(install_profile.versionInfo, ~1.12.2): jar 추출만으로 설치
//  - 신형 설치기(processors, 1.13+ 및 NeoForge): 프로세서를 직접 실행해 패치된 jar 생성
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');
const AdmZip = require('adm-zip');
const { P, ensureDir, mavenPath } = require('../paths');
const { getJson, getText, download, downloadAll, sha1File } = require('../http');
const { planLibraries } = require('../install');
const { compareMc } = require('../versions');

const FORGE_MAVEN = 'https://maven.minecraftforge.net/net/minecraftforge/forge';
const NEO_MAVEN = 'https://maven.neoforged.net/releases/net/neoforged/neoforge';
const NEO_API = 'https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge';

/* ---------------- 버전 목록 ---------------- */

async function listForgeVersions(mc) {
  const xml = await getText(`${FORGE_MAVEN}/maven-metadata.xml`);
  const all = [...xml.matchAll(/<version>([^<]+)<\/version>/g)].map(m => m[1]);
  const list = all.filter(v => v.startsWith(mc + '-'));
  let promos = {};
  try { promos = (await getJson('https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json')).promos; } catch { /* 선택 */ }
  const rec = promos[`${mc}-recommended`] || promos[`${mc}-latest`];
  const sorted = list.sort((a, b) => -compareMc(a.slice(mc.length + 1), b.slice(mc.length + 1)));
  const recommended = rec ? sorted.find(v => v === `${mc}-${rec}` || v.startsWith(`${mc}-${rec}-`)) : null;
  return sorted.map(v => ({ version: v, stable: v === recommended }));
}

function neoPrefix(mc) {
  const p = mc.split('.').map(Number);
  if (p[0] >= 26) return `${p[0]}.${p[1]}.${p[2] || 0}.`; // 26.1 → 26.1.0.x, 26.1.2 → 26.1.2.x
  if (p[0] === 1 && p[1] >= 20 && !(p[1] === 20 && (p[2] || 0) < 2)) return `${p[1]}.${p[2] || 0}.`; // 1.21.1 → 21.1.x
  return null;
}

async function listNeoForgeVersions(mc) {
  const prefix = neoPrefix(mc);
  if (!prefix) return [];
  const { versions } = await getJson(NEO_API);
  return versions
    .filter(v => v.startsWith(prefix) && !/snapshot|alpha/.test(v))
    .sort((a, b) => -compareMc(a, b))
    .map(v => ({ version: v, stable: !/beta/.test(v) }));
}

/* ---------------- 공통 설치 ---------------- */

function readZipText(zip, name) {
  const e = zip.getEntry(name.replace(/^\//, ''));
  return e ? zip.readAsText(e) : null;
}

function runJava(java, args, cwd, onLog) {
  return new Promise((resolve, reject) => {
    const p = spawn(java, args, { cwd, windowsHide: true });
    let tail = '';
    const take = d => { const s = d.toString(); tail = (tail + s).slice(-4000); onLog && onLog(s); };
    p.stdout.on('data', take); p.stderr.on('data', take);
    p.on('error', reject);
    p.on('exit', code => code === 0 ? resolve() : reject(new Error(`프로세서 실패 (code ${code})\n${tail}`)));
  });
}

function mainClassOf(jarFile) {
  const mf = new AdmZip(jarFile).readAsText('META-INF/MANIFEST.MF');
  const m = mf.match(/^Main-Class:\s*(.+)$/m);
  if (!m) throw new Error(`Main-Class 없음: ${jarFile}`);
  return m[1].trim();
}

async function installLegacy(zip, profile, mc) {
  const info = profile.versionInfo;
  if (!info.inheritsFrom) info.inheritsFrom = mc;
  if (!info.jar) info.jar = mc;
  // 유니버설 jar를 라이브러리 위치로 추출
  const dest = path.join(P.libraries, mavenPath(profile.install.path));
  ensureDir(path.dirname(dest));
  fs.writeFileSync(dest, zip.readFile(profile.install.filePath));
  ensureDir(P.versionDir(info.id));
  fs.writeFileSync(P.versionJson(info.id), JSON.stringify(info, null, 2));
  return info.id;
}

async function installModern(zip, installerPath, profile, mc, java, onProgress, onLog) {
  const versionJson = JSON.parse(readZipText(zip, profile.json || '/version.json'));
  const id = versionJson.id;
  ensureDir(P.versionDir(id));
  fs.writeFileSync(P.versionJson(id), JSON.stringify(versionJson, null, 2));

  // 설치기 안의 maven/ 폴더 → libraries/
  for (const e of zip.getEntries()) {
    if (e.isDirectory || !e.entryName.startsWith('maven/')) continue;
    const out = path.join(P.libraries, e.entryName.slice('maven/'.length));
    if (!fs.existsSync(out)) { ensureDir(path.dirname(out)); fs.writeFileSync(out, e.getData()); }
  }

  // 프로세서 실행에 필요한 라이브러리 다운로드
  const plan = planLibraries({ libraries: [...(profile.libraries || []), ...(versionJson.libraries || [])] });
  await downloadAll(plan.tasks, { onProgress, label: '로더 라이브러리' });

  // 프로세서용 변수
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'damoon-forge-'));
  const vanillaJar = P.versionJar(mc);
  const data = {
    SIDE: 'client', MINECRAFT_JAR: vanillaJar, MINECRAFT_VERSION: mc,
    ROOT: P.root, INSTALLER: installerPath, LIBRARY_DIR: P.libraries,
  };
  for (const [k, v] of Object.entries(profile.data || {})) {
    let val = v.client;
    if (val.startsWith('[') && val.endsWith(']')) val = path.join(P.libraries, mavenPath(val.slice(1, -1)));
    else if (val.startsWith("'") && val.endsWith("'")) val = val.slice(1, -1);
    else if (val.startsWith('/')) {
      const out = path.join(work, val.slice(1));
      ensureDir(path.dirname(out));
      fs.writeFileSync(out, zip.readFile(val.slice(1)));
      val = out;
    }
    data[k] = val;
  }
  const resolveArg = a => {
    if (a.startsWith('[') && a.endsWith(']')) return path.join(P.libraries, mavenPath(a.slice(1, -1)));
    return a.replace(/\{([A-Z_]+)\}/g, (m, k) => (k in data ? data[k] : m));
  };

  const sep = process.platform === 'win32' ? ';' : ':';
  const procs = (profile.processors || []).filter(p => !p.sides || p.sides.includes('client'));
  let n = 0;
  for (const proc of procs) {
    onProgress && onProgress({ label: '로더 패치', done: n++, total: procs.length });
    // 출력물이 이미 있고 해시가 맞으면 건너뜀 (재설치 속도 향상)
    if (proc.outputs) {
      let ok = true;
      for (const [k, h] of Object.entries(proc.outputs)) {
        const f = resolveArg(k), sha = resolveArg(h).replace(/'/g, '');
        if (!fs.existsSync(f) || (await sha1File(f)) !== sha) { ok = false; break; }
      }
      if (ok) continue;
    }
    const jar = path.join(P.libraries, mavenPath(proc.jar));
    const cp = [jar, ...(proc.classpath || []).map(c => path.join(P.libraries, mavenPath(c)))].join(sep);
    await runJava(java, ['-cp', cp, mainClassOf(jar), ...(proc.args || []).map(resolveArg)], work, onLog);
  }
  onProgress && onProgress({ label: '로더 패치', done: procs.length, total: procs.length });
  fs.rmSync(work, { recursive: true, force: true });
  return id;
}

async function installFromInstaller(url, cacheName, mc, java, onProgress, onLog) {
  const installerPath = path.join(P.cache, 'installers', cacheName);
  await download(url, installerPath);
  const zip = new AdmZip(installerPath);
  const profile = JSON.parse(readZipText(zip, 'install_profile.json'));
  if (profile.versionInfo) return installLegacy(zip, profile, mc);
  return installModern(zip, installerPath, profile, mc, java, onProgress, onLog);
}

async function installForge(mc, version, java, onProgress, onLog) {
  if (!version) {
    const list = await listForgeVersions(mc);
    if (!list.length) throw new Error(`Forge는 ${mc}를 지원하지 않습니다.`);
    version = (list.find(v => v.stable) || list[0]).version;
  }
  const url = `${FORGE_MAVEN}/${version}/forge-${version}-installer.jar`;
  const id = await installFromInstaller(url, `forge-${version}-installer.jar`, mc, java, onProgress, onLog);
  return { versionId: id, loaderVersion: version };
}

async function installNeoForge(mc, version, java, onProgress, onLog) {
  if (!version) {
    const list = await listNeoForgeVersions(mc);
    if (!list.length) throw new Error(`NeoForge는 ${mc}를 지원하지 않습니다. (1.20.2 이상)`);
    version = (list.find(v => v.stable) || list[0]).version;
  }
  const url = `${NEO_MAVEN}/${version}/neoforge-${version}-installer.jar`;
  const id = await installFromInstaller(url, `neoforge-${version}-installer.jar`, mc, java, onProgress, onLog);
  return { versionId: id, loaderVersion: version };
}

module.exports = { listForgeVersions, listNeoForgeVersions, installForge, installNeoForge, neoPrefix };

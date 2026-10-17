'use strict';
// 자바 런타임 자동 설치 (Mojang 공식 런타임 사용)
//   1.8.9 ~ 1.16.5 → Java 8 (jre-legacy)
//   1.17 → Java 16, 1.18 ~ 1.20.4 → Java 17, 1.20.5 ~ 1.21.11 → Java 21
//   26.1 ~ 26.3 → Java 25
// 정확한 요구 버전은 각 버전 JSON의 javaVersion.component를 그대로 따릅니다.
const fs = require('fs');
const path = require('path');
const { P, ensureDir } = require('./paths');
const { getJsonCached, getJson, downloadAll } = require('./http');

const RUNTIME_INDEX = 'https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json';

function platformKeys() {
  const a = process.arch;
  if (process.platform === 'win32') return a === 'arm64' ? ['windows-arm64', 'windows-x64'] : a === 'ia32' ? ['windows-x86'] : ['windows-x64'];
  if (process.platform === 'darwin') return a === 'arm64' ? ['mac-os-arm64', 'mac-os'] : ['mac-os'];
  return a === 'ia32' ? ['linux-i386'] : ['linux'];
}

function javaBinary(dir) {
  const exe = process.platform === 'win32' ? 'javaw.exe' : 'java';
  const candidates = [
    path.join(dir, 'bin', exe),
    path.join(dir, 'jre.bundle', 'Contents', 'Home', 'bin', exe),
  ];
  return candidates.find(f => fs.existsSync(f)) || candidates[0];
}

function requiredComponent(version) {
  return version.javaVersion?.component || 'jre-legacy';
}

async function ensureJava(version, onProgress = () => {}, { override } = {}) {
  if (override && fs.existsSync(override)) return override;
  const component = requiredComponent(version);
  const index = await getJsonCached(RUNTIME_INDEX, path.join(P.cache, 'java-runtime.json'), 24 * 3600 * 1000);

  let entry, platform;
  for (const key of platformKeys()) {
    const list = index[key]?.[component];
    if (list && list.length) { entry = list[0]; platform = key; break; }
  }
  if (!entry) throw new Error(`이 OS에서 ${component} 런타임을 찾을 수 없습니다. 설정에서 자바 경로를 직접 지정하세요.`);

  const dir = path.join(P.runtime, component, platform);
  const marker = path.join(dir, '.damoon-version');
  const bin = javaBinary(dir);
  if (fs.existsSync(marker) && fs.readFileSync(marker, 'utf8') === entry.version.name && fs.existsSync(javaBinary(dir))) return bin;

  const manifest = await getJson(entry.manifest.url);
  const tasks = [];
  const execs = [];
  const links = [];
  for (const [rel, f] of Object.entries(manifest.files)) {
    const dest = path.join(dir, rel);
    if (f.type === 'directory') ensureDir(dest);
    else if (f.type === 'file') {
      const raw = f.downloads.raw;
      tasks.push({ urls: [raw.url], dest, sha1: raw.sha1, size: raw.size });
      if (f.executable) execs.push(dest);
    } else if (f.type === 'link') links.push({ dest, target: f.target });
  }
  await downloadAll(tasks, { concurrency: 16, onProgress, label: `Java (${component})` });
  if (process.platform !== 'win32') {
    for (const e of execs) fs.chmodSync(e, 0o755);
    for (const l of links) {
      try { fs.rmSync(l.dest, { force: true }); ensureDir(path.dirname(l.dest)); fs.symlinkSync(l.target, l.dest); } catch { /* 무시 */ }
    }
  }
  fs.writeFileSync(marker, entry.version.name);
  return javaBinary(dir);
}

// 설치기 실행용(콘솔이 필요한) java 경로
function consoleJava(bin) {
  return process.platform === 'win32' ? bin.replace(/javaw\.exe$/i, 'java.exe') : bin;
}

module.exports = { ensureJava, consoleJava, requiredComponent };

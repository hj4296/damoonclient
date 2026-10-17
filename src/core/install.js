'use strict';
// 게임 파일 설치: 클라이언트 jar, 라이브러리, 네이티브, 에셋, 로깅 설정
const fs = require('fs');
const path = require('path');
const AdmZip = require('adm-zip');
const { P, ensureDir, mavenPath } = require('./paths');
const { download, downloadAll } = require('./http');
const { allowed, osName } = require('./rules');

const RESOURCES_URL = 'https://resources.download.minecraft.net';
const FALLBACK_MAVENS = [
  'https://libraries.minecraft.net/',
  'https://maven.minecraftforge.net/',
  'https://maven.neoforged.net/releases/',
  'https://maven.fabricmc.net/',
  'https://repo1.maven.org/maven2/',
];

const joinUrl = (base, p) => (base.endsWith('/') ? base : base + '/') + p;

// 라이브러리 목록 → 다운로드 작업 + 클래스패스 + 네이티브 jar 목록
function planLibraries(version) {
  const tasks = [];
  const classpath = [];
  const natives = [];
  const isArm = process.arch === 'arm64';

  for (const lib of version.libraries || []) {
    if (!allowed(lib.rules)) continue;
    if (lib.clientreq === false) continue; // 구형 Forge: 서버 전용 라이브러리

    // 1) 일반 아티팩트
    const art = lib.downloads?.artifact;
    if (art || !lib.downloads) {
      const rel = art?.path || mavenPath(lib.name);
      const dest = path.join(P.libraries, rel);
      let urls;
      if (art) urls = art.url ? [art.url] : []; // url이 비어있으면 설치기가 생성하는 파일
      else urls = [lib.url ? joinUrl(lib.url, rel) : null, ...FALLBACK_MAVENS.map(b => joinUrl(b, rel))];
      // Fabric 메타는 downloads 없이 sha1/size를 라이브러리에 직접 넣어줌
      if (urls.length) tasks.push({ urls, dest, sha1: art?.sha1 || lib.sha1, size: art?.size || lib.size });
      if (!lib.natives) classpath.push(dest);
      // 1.19+ 형식: 이름에 natives-* 분류자가 들어간 라이브러리도 압축 해제 대상
      if (/:natives-/.test(lib.name)) natives.push({ file: dest, exclude: ['META-INF/'] });
    }

    // 2) 구형 네이티브 (lib.natives 맵)
    if (lib.natives) {
      let cls = lib.natives[osName()];
      if (!cls) continue;
      cls = cls.replace('${arch}', process.arch === 'ia32' ? '32' : '64');
      // Apple Silicon: 구형 LWJGL2에는 arm64 네이티브가 없으므로 x64(Rosetta) 사용
      const c = lib.downloads?.classifiers?.[cls] || (isArm && lib.downloads?.classifiers?.[cls + '-arm64']);
      const rel = c?.path || mavenPath(`${lib.name}:${cls}`);
      const dest = path.join(P.libraries, rel);
      const urls = c?.url ? [c.url] : [lib.url ? joinUrl(lib.url, rel) : null, ...FALLBACK_MAVENS.map(b => joinUrl(b, rel))];
      tasks.push({ urls, dest, sha1: c?.sha1, size: c?.size });
      natives.push({ file: dest, exclude: lib.extract?.exclude || ['META-INF/'] });
    }
  }
  return { tasks, classpath, natives };
}

function extractNatives(natives, outDir) {
  fs.rmSync(outDir, { recursive: true, force: true });
  ensureDir(outDir);
  for (const n of natives) {
    if (!fs.existsSync(n.file)) continue;
    const zip = new AdmZip(n.file);
    for (const e of zip.getEntries()) {
      if (e.isDirectory) continue;
      if (n.exclude.some(x => e.entryName.startsWith(x))) continue;
      // 네이티브 라이브러리 파일만 평탄하게 풀기
      if (!/\.(dll|so|dylib|jnilib)$/i.test(e.entryName)) continue;
      fs.writeFileSync(path.join(outDir, path.basename(e.entryName)), e.getData());
    }
  }
}

// 에셋 설치. 반환값: 실행 시 ${assets_root}/${game_assets}로 쓸 경로
async function installAssets(version, gameDir, onProgress, quick = false) {
  const ai = version.assetIndex;
  if (!ai) return { assetsRoot: P.assets, virtualDir: P.assets };
  const idxFile = path.join(P.assets, 'indexes', `${ai.id}.json`);
  await download(ai.url, idxFile, { sha1: ai.sha1 });
  const index = JSON.parse(fs.readFileSync(idxFile, 'utf8'));
  const tasks = [];
  for (const [, obj] of Object.entries(index.objects)) {
    const sub = obj.hash.slice(0, 2);
    tasks.push({
      urls: [`${RESOURCES_URL}/${sub}/${obj.hash}`],
      dest: path.join(P.assets, 'objects', sub, obj.hash),
      sha1: obj.hash, size: obj.size,
    });
  }
  await downloadAll(tasks, { concurrency: 32, onProgress, label: '에셋', quick });

  // 아주 오래된 인덱스(legacy/pre-1.6) 호환: 가상 폴더 / resources 폴더로 복사
  const virtualDir = path.join(P.assets, 'virtual', ai.id);
  if (index.virtual || index.map_to_resources) {
    for (const [name, obj] of Object.entries(index.objects)) {
      const src = path.join(P.assets, 'objects', obj.hash.slice(0, 2), obj.hash);
      const targets = [];
      if (index.virtual) targets.push(path.join(virtualDir, name));
      if (index.map_to_resources) targets.push(path.join(gameDir, 'resources', name));
      for (const t of targets) {
        if (fs.existsSync(t)) continue;
        ensureDir(path.dirname(t));
        fs.copyFileSync(src, t);
      }
    }
  }
  return { assetsRoot: P.assets, virtualDir };
}

// 클라이언트 jar: versions/<id>/<id>.jar 로 맞춰 둠 (Forge의 ignoreList가 이 이름을 기대)
async function installClientJar(version) {
  const baseJar = P.versionJar(version.clientJarId);
  const dl = version.downloads?.client;
  if (dl) await download(dl.url, baseJar, { sha1: dl.sha1, size: dl.size });
  const jar = P.versionJar(version.id);
  if (jar !== baseJar && fs.existsSync(baseJar)) {
    const need = !fs.existsSync(jar) || fs.statSync(jar).size !== fs.statSync(baseJar).size;
    if (need) { ensureDir(path.dirname(jar)); fs.copyFileSync(baseJar, jar); }
  }
  return jar;
}

async function installLogging(version) {
  const lc = version.logging?.client;
  if (!lc?.file) return null;
  const dest = path.join(P.assets, 'log_configs', lc.file.id);
  await download(lc.file.url, dest, { sha1: lc.file.sha1 });
  return lc.argument.replace('${path}', dest);
}

// 전체 설치. 한 번 전체 검증을 통과하면 마커를 남기고, 이후 실행은 크기 비교만 해서 빠르게 시작
async function installVersion(version, gameDir, onProgress = () => {}, { verify = false } = {}) {
  const marker = path.join(P.versionDir(version.id), '.damoon-verified');
  const quick = !verify && fs.existsSync(marker);
  onProgress({ label: '클라이언트', done: 0, total: 1 });
  const clientJar = await installClientJar(version);
  const plan = planLibraries(version);
  await downloadAll(plan.tasks, { concurrency: 16, onProgress, label: '라이브러리', quick });
  const nativesDir = path.join(P.versionDir(version.id), 'natives');
  if (!quick || !fs.existsSync(nativesDir)) extractNatives(plan.natives, nativesDir);
  const assets = await installAssets(version, gameDir, onProgress, quick);
  const loggingArg = await installLogging(version);
  fs.writeFileSync(marker, new Date().toISOString());
  return { clientJar, classpath: [...plan.classpath, clientJar], nativesDir, assets, loggingArg };
}

module.exports = { installVersion, planLibraries, extractNatives, installAssets, FALLBACK_MAVENS };

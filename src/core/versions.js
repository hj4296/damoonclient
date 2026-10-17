'use strict';
// 버전 목록(1.8.9 ~ 최신) 조회 + 버전 JSON 로드/상속 병합
const fs = require('fs');
const path = require('path');
const { P, ensureDir } = require('./paths');
const { getJsonCached, download } = require('./http');

const MANIFEST_URL = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';
const MIN_VERSION = '1.8.9';

async function getManifest() {
  return getJsonCached(MANIFEST_URL, path.join(P.cache, 'version_manifest_v2.json'));
}

// 1.8.9 이후 출시된 버전만 반환 (기본: 정식 버전만)
async function listVersions({ includeSnapshots = false } = {}) {
  const m = await getManifest();
  const floor = m.versions.find(v => v.id === MIN_VERSION);
  const floorTime = floor ? Date.parse(floor.releaseTime) : 0;
  return m.versions
    .filter(v => Date.parse(v.releaseTime) >= floorTime)
    .filter(v => includeSnapshots ? (v.type === 'release' || v.type === 'snapshot') : v.type === 'release')
    .map(v => ({ id: v.id, type: v.type, releaseTime: v.releaseTime }));
}

// 버전 비교 (1.8.9 < 1.20.1 < 1.21.11 < 26.1 < 26.3)
function compareMc(a, b) {
  const pa = String(a).split(/[.\-]/).map(x => parseInt(x, 10) || 0);
  const pb = String(b).split(/[.\-]/).map(x => parseInt(x, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d;
  }
  return 0;
}

// 바닐라 버전 JSON 확보 (없으면 Mojang에서 다운로드)
async function ensureVanillaJson(id) {
  const file = P.versionJson(id);
  if (fs.existsSync(file)) return JSON.parse(fs.readFileSync(file, 'utf8'));
  const m = await getManifest();
  const entry = m.versions.find(v => v.id === id);
  if (!entry) throw new Error(`알 수 없는 버전: ${id}`);
  ensureDir(P.versionDir(id));
  await download(entry.url, file, { sha1: entry.sha1 });
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function libKey(lib) {
  const parts = lib.name.split(':');
  // group:artifact[:classifier] 기준으로 중복 제거 (버전은 무시 → 자식 버전 우선)
  return [parts[0], parts[1], parts[3] || '', lib.natives ? 'natives' : ''].join(':');
}

function mergeVersion(child, parent) {
  const merged = { ...parent, ...child };
  const seen = new Set();
  merged.libraries = [];
  for (const lib of [...(child.libraries || []), ...(parent.libraries || [])]) {
    const k = libKey(lib);
    if (seen.has(k)) continue;
    seen.add(k);
    merged.libraries.push(lib);
  }
  if (parent.arguments || child.arguments) {
    merged.arguments = {
      game: [...(parent.arguments?.game || []), ...(child.arguments?.game || [])],
      jvm: [...(parent.arguments?.jvm || []), ...(child.arguments?.jvm || [])],
    };
  }
  merged.minecraftArguments = child.minecraftArguments || parent.minecraftArguments;
  merged.mainClass = child.mainClass || parent.mainClass;
  merged.assetIndex = child.assetIndex || parent.assetIndex;
  merged.assets = child.assets || parent.assets;
  merged.downloads = child.downloads || parent.downloads;
  merged.javaVersion = child.javaVersion || parent.javaVersion;
  merged.logging = child.logging || parent.logging;
  merged.clientJarId = parent.clientJarId || parent.id;
  merged.baseVersion = parent.baseVersion || parent.id;
  delete merged.inheritsFrom;
  return merged;
}

// 버전 JSON 로드 + inheritsFrom 체인 병합 (Fabric/Forge 프로필 → 바닐라)
async function resolveVersion(id) {
  const file = P.versionJson(id);
  let json;
  if (fs.existsSync(file)) json = JSON.parse(fs.readFileSync(file, 'utf8'));
  else json = await ensureVanillaJson(id);
  if (json.inheritsFrom) {
    const parent = await resolveVersion(json.inheritsFrom);
    return mergeVersion(json, parent);
  }
  return { ...json, clientJarId: json.id, baseVersion: json.id };
}

module.exports = { MIN_VERSION, getManifest, listVersions, ensureVanillaJson, resolveVersion, compareMc, mergeVersion };
